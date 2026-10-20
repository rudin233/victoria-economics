package com.bbmurloc.victoriaeconomics.server.production.infrastructure;

import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteTransactions;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.EconomyStateCodec;
import java.sql.*;
import java.util.*;

/** Execution stores references; production_batches is the sole durable batch state. */
public final class SqliteProductionExecutionRepository implements ProductionExecutionRepository {
    private final Connection connection;
    private final Object lock;

    public SqliteProductionExecutionRepository(Connection connection, Object lock) {
        this.connection = connection;
        this.lock = lock;
    }

    @Override
    public ProductionExecution load(UUID id) {
        synchronized (lock) {
            SqliteTransactions.requireCommittedReads(connection);
            return SqliteTransactions.run(connection, () -> read(id));
        }
    }

    /** Only a live capability for this exact connection can read transaction-local facts. */
    public ProductionExecutionRepository.Session inTransaction(SqliteTransactions.WriteSession session) {
        session.requireConnection(connection);
        return new ProductionExecutionRepository.Session() {
            @Override public ProductionExecution load(UUID id) {
                session.requireConnection(connection);
                try { return session.perform(() -> read(id)); }
                catch (SQLException failure) { throw new IllegalStateException("Cannot read production execution: " + id, failure); }
            }
            @Override public void save(ProductionExecution changed, long expectedRevision) {
                session.requireConnection(connection);
                try { session.perform(() -> {persist(changed, expectedRevision);return null;}); }
                catch (SQLException failure) { throw new IllegalStateException("Cannot save production execution", failure); }
                // The owning UoW, not a savepoint, acknowledges the outer commit.
            }
        };
    }

    private ProductionExecution read(UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM production_executions WHERE building_id=?")) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Production execution missing: " + id);
                long tick=rows.getLong("processing_tick");Long processing=rows.wasNull()?null:tick;
                var execution = ProductionExecution.rehydrate(id, loadBatch(rows.getString("current_batch_id")),
                        loadBatch(rows.getString("last_batch_id")), ProductionExecution.Boundary.valueOf(rows.getString("boundary")),
                        rows.getInt("automatic") != 0, rows.getLong("execution_revision"),rows.getLong("last_completed_tick"),processing,EconomyStateCodec.decodePauseReasons(rows.getString("pause_reasons_json")));
                try (var active = connection.prepareStatement("SELECT batch_id FROM production_batches WHERE building_id=? AND status IN ('ACTIVE','PAUSED','SETTLING_COMPLETED','SETTLING_ABORTED')")) {
                    active.setString(1, id.toString());
                    try (var batches = active.executeQuery()) {
                        if (batches.next() && (!execution.hasUnfinishedBatch() || !execution.batch().getId().toString().equals(batches.getString(1))))
                            throw new IllegalStateException("Unreferenced unfinished production batch: " + id);
                    }
                }
                return execution;
            }
        }
    }
    public ProductionExecution inSnapshot(SqliteTransactions.ReadSession tx,UUID id)throws SQLException{tx.requireConnection(connection);return read(id);}

    @Override
    public void create(ProductionExecution initial) {
        if (initial.executionRevision() != 0 || initial.processingTick()!=null || initial.batch() != null || initial.lastBatch() != null || initial.boundary() != ProductionExecution.Boundary.NONE)
            throw new IllegalArgumentException("Initial execution must be empty with revision zero");
        synchronized (lock) {
            SqliteTransactions.run(connection, () -> {
                try (var statement = connection.prepareStatement("INSERT INTO production_executions(building_id,current_batch_id,last_batch_id,boundary,automatic,execution_revision,last_completed_tick,processing_tick,pause_reasons_json) VALUES(?,NULL,NULL,'NONE',?,0,?,NULL,'[]')")) {
                    statement.setString(1, initial.buildingId().toString());
                    statement.setInt(2, initial.automatic() ? 1 : 0);
                    statement.setLong(3,initial.lastCompletedTick());
                    statement.executeUpdate();
                }
                return null;
            });
        }
    }

    @Override
    public void save(ProductionExecution changed, long expectedRevision) {
        synchronized (lock) {
            SqliteTransactions.requireCommittedReads(connection);
            SqliteTransactions.run(connection, () -> {
                persist(changed, expectedRevision);
                return null;
            });
            changed.acknowledgeCommit(expectedRevision);
        }
    }

    private void persist(ProductionExecution changed, long expectedRevision) throws SQLException {
        if (changed.executionRevision() != expectedRevision) throw new ProductionRevisionConflict("Stale execution object");
        try(var s=connection.prepareStatement("SELECT last_completed_tick,processing_tick FROM production_executions WHERE building_id=?")){
            s.setString(1,changed.buildingId().toString());try(var r=s.executeQuery()){
                if(!r.next())throw new ProductionRevisionConflict("Execution missing");
                long completed=r.getLong(1),value=r.getLong(2);Long pending=r.wasNull()?null:value;
                boolean unchanged=changed.lastCompletedTick()==completed&&Objects.equals(changed.processingTick(),pending);
                boolean began=pending==null&&changed.lastCompletedTick()==completed&&changed.processingTick()!=null&&changed.processingTick()==Math.incrementExact(completed);
                boolean finished=changed.processingTick()==null&&changed.lastCompletedTick()>completed&&changed.lastCompletedTick()==Math.incrementExact(completed)&&(pending==null||pending==changed.lastCompletedTick());
                if(!unchanged&&!began&&!finished)throw new IllegalStateException("Production Tick checkpoint cannot rewind, skip or lose pending work");
            }
        }
        try (var statement = connection.prepareStatement("UPDATE production_executions SET current_batch_id=?,last_batch_id=?,boundary=?,automatic=?,execution_revision=?,last_completed_tick=?,processing_tick=?,pause_reasons_json=? WHERE building_id=? AND execution_revision=?")) {
            statement.setString(1, batchId(changed.batch()));
            statement.setString(2, batchId(changed.lastBatch()));
            statement.setString(3, changed.boundary().name());
            statement.setInt(4, changed.automatic() ? 1 : 0);
            statement.setLong(5, Math.incrementExact(expectedRevision));
            statement.setLong(6,changed.lastCompletedTick());
            if(changed.processingTick()==null)statement.setNull(7,Types.INTEGER);else statement.setLong(7,changed.processingTick());
            statement.setString(8,EconomyStateCodec.encodePauseReasons(changed.pauseReasons()));
            statement.setString(9, changed.buildingId().toString());
            statement.setLong(10, expectedRevision);
            if (statement.executeUpdate() != 1) throw new ProductionRevisionConflict("Execution missing or stale: " + changed.buildingId());
        }
        saveBatch(changed.batch());
        saveBatch(changed.lastBatch());
    }

    private static String batchId(ProductionBatch batch) { return batch == null ? null : batch.getId().toString(); }

    private ProductionBatch loadBatch(String id) throws SQLException {
        if (id == null) return null;
        try (var statement = connection.prepareStatement("SELECT * FROM production_batches WHERE batch_id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Referenced batch missing: " + id);
                return EconomyStateCodec.decodeBatch(UUID.fromString(id), UUID.fromString(rows.getString("building_id")),
                        rows.getString("configuration_json"),
                        rows.getLong("progress_units"), rows.getString("status"));
            }
        }
    }

    private void saveBatch(ProductionBatch batch) throws SQLException {
        if (batch == null) return;
        ProductionBatch prior = null;
        try (var statement = connection.prepareStatement("SELECT batch_id FROM production_batches WHERE batch_id=?")) {
            statement.setString(1, batch.getId().toString());
            try (var rows = statement.executeQuery()) { if (rows.next()) prior = loadBatch(batch.getId().toString()); }
        }
        if (prior == null) {
            if (batch.getStatus() != ProductionBatch.Status.ACTIVE || batch.getProgressUnits() != 0)
                throw new IllegalStateException("A new batch must start ACTIVE at zero progress");
            try (var statement = connection.prepareStatement("INSERT INTO production_batches(batch_id,building_id,configuration_json,progress_units,status) VALUES(?,?,?,0,'ACTIVE')")) {
                statement.setString(1, batch.getId().toString());
                statement.setString(2, batch.getBuildingId().toString());
                statement.setString(3, EconomyStateCodec.encodeBatchConfiguration(batch.getConfiguration()));
                statement.executeUpdate();
            }
            return;
        }
        if (!prior.getBuildingId().equals(batch.getBuildingId()) || !prior.getConfiguration().equals(batch.getConfiguration()))
            throw new IllegalStateException("Immutable batch commitment changed: " + batch.getId());
        boolean progressing = (prior.isActive() || prior.getStatus() == ProductionBatch.Status.PAUSED)
                && (batch.isActive() || batch.getStatus() == ProductionBatch.Status.SETTLING_COMPLETED);
        if (batch.getProgressUnits() < prior.getProgressUnits() || !allowed(prior.getStatus(), batch.getStatus())
                || (!progressing && batch.getProgressUnits() != prior.getProgressUnits()))
            throw new IllegalStateException("Illegal durable batch transition: " + batch.getId());
        if (prior.getProgressUnits() == batch.getProgressUnits() && prior.getStatus() == batch.getStatus()) return;
        try (var statement = connection.prepareStatement("UPDATE production_batches SET progress_units=?,status=? WHERE batch_id=?")) {
            statement.setLong(1, batch.getProgressUnits());
            statement.setString(2, batch.getStatus().name());
            statement.setString(3, batch.getId().toString());
            statement.executeUpdate();
        }
    }

    private static boolean allowed(ProductionBatch.Status from, ProductionBatch.Status to) {
        if (from == to) return true;
        return switch (from) {
            case ACTIVE -> to == ProductionBatch.Status.PAUSED || to == ProductionBatch.Status.SETTLING_COMPLETED || to == ProductionBatch.Status.SETTLING_ABORTED;
            case PAUSED -> to == ProductionBatch.Status.ACTIVE || to == ProductionBatch.Status.SETTLING_COMPLETED || to == ProductionBatch.Status.SETTLING_ABORTED;
            case SETTLING_COMPLETED -> to == ProductionBatch.Status.COMPLETED;
            case SETTLING_ABORTED -> to == ProductionBatch.Status.ABORTED;
            case COMPLETED, ABORTED -> false;
        };
    }
}
