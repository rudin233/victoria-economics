package com.bbmurloc.victoriaeconomics.server.production.infrastructure;

import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.diagnostics.EconomyDiagnostics;
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
        // Only immutable, strictly decoded commitments are reused, and only inside this capability.
        // Every save still reads the current row and compares its exact JSON before using one.
        var verified = new HashMap<String, VerifiedConfiguration>();
        return new ProductionExecutionRepository.Session() {
            @Override public ProductionExecution load(UUID id) {
                session.requireConnection(connection);
                try { return session.perform(() -> read(id, verified)); }
                catch (SQLException failure) { throw new IllegalStateException("Cannot read production execution: " + id, failure); }
            }
            @Override public void save(ProductionExecution changed, long expectedRevision) {
                session.requireConnection(connection);
                try { session.perform(() -> {persist(changed, expectedRevision, verified);return null;}); }
                catch (SQLException failure) { throw new IllegalStateException("Cannot save production execution", failure); }
                // The owning UoW, not a savepoint, acknowledges the outer commit.
            }
        };
    }

    private ProductionExecution read(UUID id) throws SQLException {
        return read(id, null);
    }
    private record VerifiedConfiguration(String json, ProductionBatchConfiguration configuration) {}
    private ProductionExecution read(UUID id, Map<String, VerifiedConfiguration> verified) throws SQLException {
        EconomyDiagnostics.count("repository.execution.read");
        try(var measurement=EconomyDiagnostics.stage("production.execution.load")){
        // Both frozen commitments belong to this same snapshot. SQLite flattens these simple
        // projections and looks up both batch primary keys; no cross-transaction object cache.
        try (var statement = connection.prepareStatement("""
                SELECT * FROM production_executions e
                LEFT JOIN (SELECT batch_id AS current_id, building_id AS current_owner,
                    configuration_json AS current_configuration, progress_units AS current_progress,
                    status AS current_status FROM production_batches) c ON c.current_id=e.current_batch_id
                LEFT JOIN (SELECT batch_id AS last_id, building_id AS last_owner,
                    configuration_json AS last_configuration, progress_units AS last_progress,
                    status AS last_status FROM production_batches) l ON l.last_id=e.last_batch_id
                WHERE e.building_id=?
                """)) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Production execution missing: " + id);
                long tick=rows.getLong("processing_tick");Long processing=rows.wasNull()?null:tick;
                var execution = ProductionExecution.rehydrate(id, joinedBatch(rows, "current", verified),
                        joinedBatch(rows, "last", verified), ProductionExecution.Boundary.valueOf(rows.getString("boundary")),
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
        persist(changed, expectedRevision, null);
    }
    private void persist(ProductionExecution changed, long expectedRevision, Map<String, VerifiedConfiguration> verified) throws SQLException {
        try(var measurement=EconomyDiagnostics.stage("production.execution.save")){
        try(var s=connection.prepareStatement("SELECT b.lifecycle,o.id,e.current_batch_id,e.last_batch_id FROM economic_buildings b JOIN production_executions e ON e.building_id=b.id LEFT JOIN building_lifecycle_operations o ON o.building_id=b.id WHERE b.id=?")) {
            s.setString(1,changed.buildingId().toString());try(var r=s.executeQuery()) {
                if(!r.next())throw new IllegalStateException("Unknown building identity");
                if(!r.getString(1).equals("EXISTING")||r.getString(2)!=null) {
                    if(changed.automatic()||(changed.batch()!=null&&(changed.batch().isActive()||changed.batch().getStatus()==ProductionBatch.Status.PAUSED)))
                        throw new IllegalStateException("Lifecycle identity blocks new or resumed production");
                    if(changed.batch()!=null&&!changed.batch().getId().toString().equals(r.getString("current_batch_id")))
                        throw new IllegalStateException("Lifecycle identity cannot acquire another Batch even in a settling state");
                    if(changed.lastBatch()!=null&&!changed.lastBatch().getId().toString().equals(r.getString("last_batch_id"))&&!changed.lastBatch().getId().toString().equals(r.getString("current_batch_id")))
                        throw new IllegalStateException("Lifecycle identity cannot invent another historical Batch");
                }
            }
        }
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
        saveBatch(changed.batch(), verified);
        saveBatch(changed.lastBatch(), verified);
        }
    }

    private static String batchId(ProductionBatch batch) { return batch == null ? null : batch.getId().toString(); }

    private ProductionBatch joinedBatch(ResultSet rows, String prefix, Map<String, VerifiedConfiguration> verified) throws SQLException {
        String id = rows.getString(prefix + "_batch_id");
        if (id == null) return null;
        if (rows.getString(prefix + "_id") == null) throw new IllegalStateException("Referenced batch missing: " + id);
        return decodeBatch(id, rows.getString(prefix + "_owner"), rows.getString(prefix + "_configuration"),
                rows.getLong(prefix + "_progress"), rows.getString(prefix + "_status"), verified);
    }

    private ProductionBatch findBatch(String id, Map<String, VerifiedConfiguration> verified) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM production_batches WHERE batch_id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return decodeBatch(id, rows.getString("building_id"), rows.getString("configuration_json"),
                        rows.getLong("progress_units"), rows.getString("status"), verified);
            }
        }
    }

    private ProductionBatch decodeBatch(String id, String owner, String json, long progress, String status,
                                        Map<String, VerifiedConfiguration> verified) {
        var prior = verified == null ? null : verified.get(id);
        if (prior != null && prior.json().equals(json)) {
            EconomyDiagnostics.count("json.batch.reused");
            try {
                // Progress/status/owner always come from the fresh row, never from a previous read.
                return new ProductionBatch(UUID.fromString(id), UUID.fromString(owner), prior.configuration(), progress, ProductionBatch.Status.valueOf(status));
            } catch (RuntimeException failure) { throw new IllegalStateException("Invalid current Batch state: " + id, failure); }
        }
        var decoded = EconomyStateCodec.decodeBatch(UUID.fromString(id), UUID.fromString(owner), json, progress, status);
        if (verified != null) verified.put(id, new VerifiedConfiguration(json, decoded.getConfiguration()));
        return decoded;
    }

    private void saveBatch(ProductionBatch batch, Map<String, VerifiedConfiguration> verified) throws SQLException {
        if (batch == null) return;
        ProductionBatch prior = findBatch(batch.getId().toString(), verified);
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
