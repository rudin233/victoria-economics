package com.bbmurloc.victoriaeconomics.server.time.infrastructure;

import com.bbmurloc.victoriaeconomics.server.time.domain.EconomicTime;
import com.bbmurloc.victoriaeconomics.server.time.port.EconomicTimeRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import java.sql.*;

public final class SqliteEconomicTimeRepository implements EconomicTimeRepository {
    private final Connection connection;
    private final Object lock;
    public SqliteEconomicTimeRepository(Connection connection, Object lock) { this.connection = connection; this.lock = lock; }
    @Override public EconomicTime load() { synchronized (lock) { SqliteTransactions.requireCommittedReads(connection); return read(connection); } }
    public static EconomicTime inTransaction(Connection connection, SqliteTransactions.WriteSession tx) { tx.requireConnection(connection); return read(connection); }
    public static EconomicTime inSnapshot(Connection connection, SqliteTransactions.ReadSession tx) { tx.requireConnection(connection); return read(connection); }
    private static EconomicTime read(Connection connection) {
        try (var s = connection.createStatement(); var r = s.executeQuery("SELECT t.*,p.processed_tick FROM economic_time t JOIN payroll_runtime p ON p.id=t.id")) {
            if (!r.next()) throw new IllegalStateException("Persistent economic time missing");
            long gap = r.getLong("first_uncertain_production_tick"); Long uncertain = r.wasNull() ? null : gap;
            var time = new EconomicTime(r.getLong("accepted_tick"), r.getLong("processed_tick"), r.getLong("production_callback_returned_tick"), uncertain, r.getLong("revision"));
            if (r.next()) throw new IllegalStateException("Multiple world clocks");
            return time;
        } catch (SQLException failure) { throw new IllegalStateException("Cannot read economic time", failure); }
    }
    @Override public EconomicTime acceptNext(long expected) { synchronized (lock) {
        return SqliteTransactions.write(connection, tx -> {
            var before = read(connection);
            if (before.acceptedTick() != expected) throw new IllegalStateException("Economic tick CAS conflict; verify accepted time before retry");
            long next = Math.incrementExact(expected), revision = Math.incrementExact(before.revision());
            if(!before.payrollCaughtUp())throw new IllegalStateException("Previous accepted Tick payroll is not complete");
            try(var s=connection.prepareStatement("SELECT 1 FROM production_executions e LEFT JOIN production_batches b ON b.batch_id=e.current_batch_id WHERE e.last_completed_tick<? OR e.processing_tick IS NOT NULL OR e.boundary<>'NONE' OR b.status IN ('SETTLING_COMPLETED','SETTLING_ABORTED') UNION ALL SELECT 1 FROM production_start_intents")){
                s.setLong(1,expected);try(var r=s.executeQuery()){if(r.next())throw new IllegalStateException("Previous accepted Tick has unfinished production facts");}
            }
            try (var s = connection.prepareStatement("UPDATE economic_time SET accepted_tick=?,revision=?,first_uncertain_production_tick=CASE WHEN first_uncertain_production_tick IS NULL AND production_callback_returned_tick<accepted_tick THEN production_callback_returned_tick+1 ELSE first_uncertain_production_tick END WHERE id=1 AND revision=?")) {
                s.setLong(1, next); s.setLong(2, revision); s.setLong(3, before.revision());
                if (s.executeUpdate() != 1) throw new IllegalStateException("Economic tick CAS conflict");
            }
            return read(connection);
        });
    } }
    @Override public void acknowledgeProductionCallback(long tick) { synchronized (lock) {
        SqliteTransactions.write(connection, tx -> {
            var before = read(connection);
            if(tick==before.acceptedTick()&&tick==before.productionCallbackReturnedTick())return null;
            if (tick != before.acceptedTick() || tick <= before.productionCallbackReturnedTick()) throw new IllegalStateException("Invalid production callback acknowledgement");
            try (var s = connection.prepareStatement("UPDATE economic_time SET production_callback_returned_tick=?,revision=? WHERE id=1 AND revision=?")) {
                s.setLong(1, tick); s.setLong(2, Math.incrementExact(before.revision())); s.setLong(3, before.revision());
                if (s.executeUpdate() != 1) throw new IllegalStateException("Economic time revision conflict");
            }
            return null;
        });
    } }
    @Override public void recordRecoveryGap() { synchronized (lock) {
        // No write on a clean restart. A gap is evidence to retain, never permission to replay production.
        if (!load().hasUnconfirmedProductionCallback()) return;
        SqliteTransactions.write(connection, tx -> {
            var before = read(connection);
            if (before.firstUncertainProductionTick() == null && before.productionCallbackReturnedTick() < before.acceptedTick())
                try (var s = connection.prepareStatement("UPDATE economic_time SET first_uncertain_production_tick=?,revision=? WHERE id=1 AND revision=?")) {
                    s.setLong(1, Math.incrementExact(before.productionCallbackReturnedTick())); s.setLong(2, Math.incrementExact(before.revision())); s.setLong(3, before.revision());
                    if (s.executeUpdate() != 1) throw new IllegalStateException("Economic time revision conflict");
                }
            return null;
        });
    } }
}
