package com.bbmurloc.victoriaeconomics.server.payroll.infrastructure;

import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import com.bbmurloc.victoriaeconomics.server.production.port.BuildingPayrollPort;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import java.sql.*;
import java.util.UUID;

/** Isolated adapter. Do not wire into the legacy ARREARS->abort production loop before task C. */
public final class SqliteBuildingPayrollQuery implements BuildingPayrollPort {
    private final Connection connection;
    private final Object lock;
    public SqliteBuildingPayrollQuery(Connection connection, Object lock) { this.connection = connection; this.lock = lock; }
    @Override public State state(UUID building) { return state(building, null); }
    public State state(UUID building, CompanyId expectedCompany) { synchronized (lock) {
        try { return SqliteTransactions.read(connection, tx -> {
            var time = SqliteEconomicTimeRepository.inSnapshot(connection, tx);
            try (var s = connection.createStatement(); var r = s.executeQuery("SELECT authority FROM payroll_runtime WHERE id=1")) {
                if (!r.next() || !r.getString(1).equals("BUILDING_STAFFING")) return State.UNAVAILABLE;
            }
            String company;
            try (var s = connection.prepareStatement("SELECT owner_company_id FROM economic_buildings WHERE id=?")) {
                s.setString(1, building.toString()); try (var r = s.executeQuery()) { if (!r.next()) return State.UNAVAILABLE; company = r.getString(1); }
            }
            if (expectedCompany != null && !expectedCompany.toString().equals(company)) return State.UNAVAILABLE;
            if (!time.payrollCaughtUp()) return State.RECOVERY;
            boolean active = false; long unpaid = 0;
            try (var s = connection.prepareStatement("SELECT * FROM payroll_accruals WHERE building_id=? AND company_id=?")) {
                s.setString(1, building.toString()); s.setString(2, company);
                try (var r = s.executeQuery()) { while (r.next()) {
                    var p = SqlitePayrollRepository.decode(r); unpaid = Math.addExact(unpaid, p.unpaid());
                    if (p.closedTick() == null) {
                        active = true;
                        if (p.lastAccruedTick() != time.acceptedTick()) return State.RECOVERY;
                        long count = 0;
                        try (var q = connection.prepareStatement("SELECT employed FROM building_staffing_roles WHERE building_id=?")) {
                            q.setString(1, building.toString()); try (var employees = q.executeQuery()) { while (employees.next()) count = Math.addExact(count, employees.getLong(1)); }
                        }
                        if (count != p.employees()) return State.RECOVERY;
                        try (var q = connection.prepareStatement("SELECT 1 FROM regional_wage_rates WHERE region_id=? AND currency_id=? AND effective_tick<=?")) {
                            q.setString(1, p.region().toString()); q.setString(2, p.currency().toString()); q.setLong(3, time.acceptedTick());
                            try (var rate = q.executeQuery()) { if (!rate.next()) return State.UNAVAILABLE; }
                        }
                    }
                } }
            }
            if (!active) return State.UNAVAILABLE;
            return unpaid > 0 ? State.ARREARS : State.CURRENT;
        }); } catch (CommitOutcomeUnknownException failure) { return State.RECOVERY; }
        catch (RuntimeException failure) { return State.UNAVAILABLE; }
    } }
}
