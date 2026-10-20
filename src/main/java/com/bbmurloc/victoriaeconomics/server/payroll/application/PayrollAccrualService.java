package com.bbmurloc.victoriaeconomics.server.payroll.application;

import com.bbmurloc.victoriaeconomics.server.payroll.domain.*;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.SqlitePayrollRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import java.sql.Connection;

/** Accrual only. There is no payment, hiring, authority switch, or enrollment command here. */
public final class PayrollAccrualService {
    private final Connection connection;
    private final Object lock;
    private final SqlitePayrollRepository repository;
    public PayrollAccrualService(Connection connection, Object lock) { this.connection = connection; this.lock = lock; repository = new SqlitePayrollRepository(connection, lock); }
    public void configureRate(RegionalWageRate rate) { synchronized (lock) {
        SqliteTransactions.write(connection, tx -> { repository.inTransaction(tx).addRate(rate); return null; });
    } }
    /** Recovery is bounded by accepted SQLite time, never wall time; rerunning cannot duplicate a period. */
    public void recoverAcceptedTime() { synchronized (lock) {
        SqliteTransactions.write(connection, tx -> {
            var time = SqliteEconomicTimeRepository.inTransaction(connection, tx);
            var session = repository.inTransaction(tx); var segments = session.all();
            if (!segments.isEmpty()) session.requireStaffingAuthority();
            try (var s = connection.createStatement(); var r = s.executeQuery("SELECT b.id FROM economic_buildings b JOIN building_staffing_roles s ON s.building_id=b.id LEFT JOIN payroll_accruals p ON p.building_id=b.id AND p.closed_tick IS NULL WHERE s.employed>0 AND p.id IS NULL")) {
                if (r.next()) throw new IllegalStateException("Employed Staffing has no trusted payroll enrollment; cursor cannot skip its wage history");
            }
            // A caught-up global cursor alone never establishes a building's enrollment or wage readiness.
            for (var segment : segments) {
                session.validateEmployees(segment);
                session.accrue(segment, time.acceptedTick());
            }
            try (var s = connection.prepareStatement("UPDATE payroll_runtime SET processed_tick=?,revision=revision+1 WHERE id=1 AND processed_tick=? AND revision<9223372036854775807")) {
                s.setLong(1, time.acceptedTick()); s.setLong(2, time.payrollTick());
                if (s.executeUpdate() != 1) throw new IllegalStateException("Payroll cursor CAS/overflow conflict");
            }
            return null;
        });
    } }
}
