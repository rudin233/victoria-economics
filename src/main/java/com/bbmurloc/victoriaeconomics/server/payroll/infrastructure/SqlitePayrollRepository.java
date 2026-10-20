package com.bbmurloc.victoriaeconomics.server.payroll.infrastructure;

import com.bbmurloc.victoriaeconomics.server.payroll.domain.*;
import com.bbmurloc.victoriaeconomics.server.payroll.port.PayrollRepository;
import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import com.bbmurloc.victoriaeconomics.server.finance.domain.CurrencyId;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import java.sql.*;
import java.util.*;

/** Normalized v6 contract. Session methods are foundations for task C, not formal staffing commands. */
public final class SqlitePayrollRepository implements PayrollRepository {
    private final Connection connection;
    private final Object lock;
    public SqlitePayrollRepository(Connection connection, Object lock) { this.connection = connection; this.lock = lock; }
    @Override public PayrollAccrual load(UUID id) { synchronized (lock) { SqliteTransactions.requireCommittedReads(connection);
        try { return read(id); } catch (SQLException e) { throw new IllegalStateException("Cannot load payroll segment", e); }
    } }
    @Override public List<PayrollLiability> liabilities(UUID id) { synchronized (lock) {
        return SqliteTransactions.read(connection, tx -> {
            read(id); var result = new ArrayList<PayrollLiability>();
            try (var s = connection.prepareStatement("SELECT * FROM payroll_liabilities WHERE segment_id=? ORDER BY period_id")) {
                s.setString(1, id.toString()); try (var r = s.executeQuery()) {
                    while (r.next()) result.add(new PayrollLiability(id, r.getLong("period_id"), r.getLong("amount"), r.getLong("paid")));
                }
            }
            return List.copyOf(result);
        });
    } }
    public static PayrollAccrual decode(ResultSet r) throws SQLException {
        long closed = r.getLong("closed_tick"); Long closedTick = r.wasNull() ? null : closed;
        return new PayrollAccrual(UUID.fromString(r.getString("id")), UUID.fromString(r.getString("building_id")), CompanyId.parse(r.getString("company_id")), RegionId.parse(r.getString("region_id")),
                CurrencyId.parse(r.getString("currency_id")), r.getLong("start_tick"), r.getLong("last_accrued_tick"), closedTick, r.getLong("employees"), r.getLong("accrued"),
                r.getLong("unmatured"), r.getLong("matured"), r.getLong("paid"), r.getLong("remainder"), r.getLong("revision"));
    }
    private PayrollAccrual read(UUID id) throws SQLException {
        try (var s = connection.prepareStatement("SELECT * FROM payroll_accruals WHERE id=?")) {
            s.setString(1, id.toString()); try (var r = s.executeQuery()) { if (!r.next()) throw new IllegalStateException("Payroll segment missing"); return decode(r); }
        }
    }
    public Session inTransaction(SqliteTransactions.WriteSession tx) { tx.requireConnection(connection); return new Session(tx); }
    public final class Session {
        private final SqliteTransactions.WriteSession tx;
        private Session(SqliteTransactions.WriteSession tx) { this.tx = tx; }
        public PayrollAccrual load(UUID id) throws SQLException { return tx.perform(() -> read(id)); }
        public List<PayrollAccrual> all() throws SQLException { return tx.perform(() -> {
            var result = new ArrayList<PayrollAccrual>();
            try (var s = connection.createStatement(); var r = s.executeQuery("SELECT * FROM payroll_accruals ORDER BY id")) { while (r.next()) result.add(decode(r)); }
            return List.copyOf(result);
        }); }
        public List<RegionalWageRate> rates(RegionId region, CurrencyId currency) throws SQLException { return tx.perform(() -> {
            var result = new ArrayList<RegionalWageRate>();
            try (var s = connection.prepareStatement("SELECT * FROM regional_wage_rates WHERE region_id=? AND currency_id=? ORDER BY effective_tick")) {
                s.setString(1, region.toString()); s.setString(2, currency.toString());
                try (var r = s.executeQuery()) { while (r.next()) result.add(new RegionalWageRate(region, currency, r.getLong("effective_tick"), r.getLong("amount"), r.getLong("revision"))); }
            }
            return List.copyOf(result);
        }); }
        public void addRate(RegionalWageRate rate) throws SQLException { tx.perform(() -> {
            long accepted = SqliteEconomicTimeRepository.inTransaction(connection, tx).acceptedTick();
            if (rate.effectiveTick() < accepted) throw new IllegalArgumentException("Wage rate cannot change accepted history");
            var history = rates(rate.region(), rate.currency());
            long revision = history.isEmpty() ? 0 : Math.incrementExact(history.getLast().revision());
            if (rate.revision() != revision || (!history.isEmpty() && rate.effectiveTick() <= history.getLast().effectiveTick())) throw new IllegalStateException("Wage history revision/order conflict");
            try (var s = connection.prepareStatement("SELECT currency_id FROM regional_wage_rates WHERE region_id=?")) {
                s.setString(1, rate.region().toString()); try (var r = s.executeQuery()) { while (r.next()) if (!rate.currency().toString().equals(r.getString(1))) throw new IllegalStateException("Region currency change needs explicit future policy"); }
            }
            // Settle old history up to the accepted change instant in this same transaction.
            for (var segment : all()) if (segment.region().equals(rate.region()) && segment.lastAccruedTick() < accepted)
                accrue(segment, accepted);
            try (var s = connection.prepareStatement("INSERT INTO regional_wage_rates VALUES(?,?,?,?,?)")) {
                s.setString(1, rate.region().toString()); s.setString(2, rate.currency().toString()); s.setLong(3, rate.effectiveTick()); s.setLong(4, rate.amount()); s.setLong(5, rate.revision()); s.executeUpdate();
            }
            return null;
        }); }
        /** Explicit enrollment at the accepted instant. Formal server exposes no enrollment entry point yet. */
        public PayrollAccrual enroll(UUID segmentId, UUID building, CurrencyId currency) throws SQLException { return tx.perform(() -> {
            requireStaffingAuthority(); long tick = SqliteEconomicTimeRepository.inTransaction(connection, tx).acceptedTick();
            String company, region;
            try (var s = connection.prepareStatement("SELECT owner_company_id,region_id,lifecycle FROM economic_buildings WHERE id=?")) {
                s.setString(1, building.toString()); try (var r = s.executeQuery()) {
                    if (!r.next() || !r.getString("lifecycle").equals("EXISTING")) throw new IllegalStateException("Live Building identity required");
                    company = r.getString(1); region = r.getString(2);
                }
            }
            long employees = employees(building);
            var result = PayrollAccrual.start(segmentId, building, CompanyId.parse(company), RegionId.parse(region), currency, tick, employees);
            if (rates(result.region(), currency).stream().noneMatch(rate -> rate.effectiveTick() <= tick)) throw new IllegalStateException("Configured regional wage rate required");
            try (var s = connection.prepareStatement("INSERT INTO payroll_accruals VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                bind(s, result); s.executeUpdate();
            }
            recordEmployees(result);
            return result;
        }); }
        public PayrollAccrual accrue(PayrollAccrual prior, long target) throws SQLException { return tx.perform(() -> {
            if (!read(prior.id()).equals(prior)) throw new IllegalStateException("Stale or untrusted payroll segment snapshot");
            validateEmployees(prior);
            if (target > SqliteEconomicTimeRepository.inTransaction(connection, tx).acceptedTick()) throw new IllegalArgumentException("Cannot accrue unaccepted time");
            var result = prior.accrueThrough(target, rates(prior.region(), prior.currency()));
            if (!result.accrual().equals(prior)) save(result.accrual(), prior.revision());
            try (var s = connection.prepareStatement("INSERT INTO payroll_liabilities VALUES(?,?,?,0)")) {
                for (var liability : result.liabilities()) {
                    s.setString(1, prior.id().toString()); s.setLong(2, liability.periodId()); s.setLong(3, liability.amount()); s.executeUpdate();
                }
            }
            return result.accrual();
        }); }
        /** Future UoW: accrue old base, save Staffing, then acknowledge its new base in this same session. */
        public PayrollAccrual changeEmployees(UUID id, long count, long tick) throws SQLException { return tx.perform(() -> {
            requireStaffingAuthority();
            if (tick != SqliteEconomicTimeRepository.inTransaction(connection, tx).acceptedTick()) throw new IllegalArgumentException("Employee change must use accepted current tick");
            var prior = read(id);
            if (prior.lastAccruedTick() != tick || employees(prior.buildingId()) != count)
                throw new IllegalStateException("Accrue old headcount first and persist matching Staffing in the shared transaction");
            var changed = prior.withEmployeesAtCursor(count, tick);
            save(changed, prior.revision()); recordEmployees(changed); return changed;
        }); }
        public PayrollAccrual close(UUID id, long tick) throws SQLException { return tx.perform(() -> {
            if (tick != SqliteEconomicTimeRepository.inTransaction(connection, tx).acceptedTick()) throw new IllegalArgumentException("Close at accepted tick only");
            var prior = accrue(read(id), tick); var closed = prior.closeAtCursor(tick); save(closed, prior.revision()); return closed;
        }); }
        public void requireStaffingAuthority() throws SQLException {
            tx.requireConnection(connection);
            try (var s = connection.createStatement(); var r = s.executeQuery("SELECT authority FROM payroll_runtime WHERE id=1")) {
                if (!r.next() || !r.getString(1).equals("BUILDING_STAFFING")) throw new IllegalStateException("Formal employment authority has not switched to BuildingStaffing");
            }
        }
        public void validateEmployees(PayrollAccrual segment) throws SQLException { tx.perform(() -> {
            if (segment.closedTick() == null) {
                if (employees(segment.buildingId()) != segment.employees()) throw new IllegalStateException("Staffing changed without payroll cutoff");
                try (var s = connection.prepareStatement("SELECT owner_company_id,region_id FROM economic_buildings WHERE id=?")) {
                    s.setString(1, segment.buildingId().toString()); try (var r = s.executeQuery()) {
                        if (!r.next() || !segment.company().toString().equals(r.getString(1)) || !segment.region().toString().equals(r.getString(2))) throw new IllegalStateException("Payroll ownership segment cutoff required");
                    }
                }
            }
            return null;
        }); }
        private void save(PayrollAccrual changed, long expected) throws SQLException {
            if (changed.revision() != Math.incrementExact(expected)) throw new IllegalArgumentException("Payroll CAS revision");
            try (var s = connection.prepareStatement("UPDATE payroll_accruals SET last_accrued_tick=?,closed_tick=?,employees=?,accrued=?,unmatured=?,matured=?,paid=?,remainder=?,revision=? WHERE id=? AND revision=?")) {
                s.setLong(1, changed.lastAccruedTick()); if (changed.closedTick() == null) s.setNull(2, Types.INTEGER); else s.setLong(2, changed.closedTick());
                s.setLong(3, changed.employees()); s.setLong(4, changed.accrued()); s.setLong(5, changed.unmatured()); s.setLong(6, changed.matured());
                s.setLong(7, changed.paid()); s.setLong(8, changed.remainder()); s.setLong(9, changed.revision()); s.setString(10, changed.id().toString()); s.setLong(11, expected);
                if (s.executeUpdate() != 1) throw new IllegalStateException("Payroll CAS conflict");
            }
        }
        private void recordEmployees(PayrollAccrual segment) throws SQLException {
            try (var s = connection.prepareStatement("INSERT INTO payroll_employment_history VALUES(?,?,?,?)")) {
                s.setString(1, segment.id().toString()); s.setLong(2, segment.lastAccruedTick()); s.setLong(3, segment.employees()); s.setLong(4, segment.revision()); s.executeUpdate();
            }
        }
    }
    private long employees(UUID id) throws SQLException {
        long result = 0;
        try (var s = connection.prepareStatement("SELECT employed FROM building_staffing_roles WHERE building_id=?")) {
            s.setString(1, id.toString()); try (var r = s.executeQuery()) { while (r.next()) result = Math.addExact(result, r.getLong(1)); }
        }
        return result;
    }
    private static void bind(PreparedStatement s, PayrollAccrual p) throws SQLException {
        s.setString(1, p.id().toString()); s.setString(2, p.buildingId().toString()); s.setString(3, p.company().toString()); s.setString(4, p.region().toString()); s.setString(5, p.currency().toString());
        s.setLong(6, p.startTick()); s.setLong(7, p.lastAccruedTick()); if (p.closedTick() == null) s.setNull(8, Types.INTEGER); else s.setLong(8, p.closedTick());
        s.setLong(9, p.employees()); s.setLong(10, p.accrued()); s.setLong(11, p.unmatured()); s.setLong(12, p.matured()); s.setLong(13, p.paid()); s.setLong(14, p.remainder()); s.setLong(15, p.revision());
        // 15 columns, deliberately explicit to keep the normalized contract auditable.
    }
}
