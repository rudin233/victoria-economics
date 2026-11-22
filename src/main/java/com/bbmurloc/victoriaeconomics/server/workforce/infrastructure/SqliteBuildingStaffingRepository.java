package com.bbmurloc.victoriaeconomics.server.workforce.infrastructure;

import com.bbmurloc.victoriaeconomics.server.workforce.domain.BuildingStaffing;
import com.bbmurloc.victoriaeconomics.server.workforce.port.BuildingStaffingRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import java.sql.*;
import java.util.*;

/** 临时用工仓储。占用只通过开工和终结事务更改。 */
public final class SqliteBuildingStaffingRepository implements BuildingStaffingRepository {
    private final Connection connection;
    private final Object lock;
    public SqliteBuildingStaffingRepository(Connection connection, Object lock) {
        this.connection = connection;
        this.lock = lock;
    }
    @Override public BuildingStaffing load(UUID id) {
        synchronized (lock) { return SqliteTransactions.read(connection, tx -> inSnapshot(tx, id)); }
    }
    public BuildingStaffing inSnapshot(SqliteTransactions.ReadSession tx, UUID id) { tx.requireConnection(connection); return read(id); }
    public Session inTransaction(SqliteTransactions.WriteSession tx) {
        tx.requireConnection(connection);
        return new Session() {
            @Override public BuildingStaffing load(UUID id) {
                try { return tx.perform(() -> read(id)); }
                catch (SQLException failure) { throw new IllegalStateException("Cannot read staffing", failure); }
            }
            @Override public void createEmpty(UUID id) {
                try { tx.perform(() -> {
                    try (var statement = connection.prepareStatement("INSERT INTO building_staffing(building_id,revision) VALUES(?,0)")) {
                        statement.setString(1, id.toString()); statement.executeUpdate();
                    }
                    return null;
                }); } catch (SQLException failure) { throw new IllegalStateException("Cannot initialize staffing", failure); }
            }
            @Override public void acquire(UUID building, UUID batch, Map<String, Integer> workers, long expectedRevision) {
                try { tx.perform(() -> {
                    var allocation = new BuildingStaffing(building, Objects.requireNonNull(batch), workers, Math.incrementExact(expectedRevision));
                    try (var query = connection.prepareStatement("""
                            SELECT 1 FROM production_executions e
                            JOIN production_batches b ON b.batch_id=e.current_batch_id AND b.building_id=e.building_id
                            JOIN economic_buildings identity ON identity.id=e.building_id
                            LEFT JOIN building_lifecycle_operations op ON op.building_id=e.building_id
                            WHERE e.building_id=? AND b.batch_id=? AND b.status='ACTIVE'
                                AND identity.lifecycle='EXISTING' AND op.id IS NULL
                            """)) {
                        query.setString(1, building.toString());
                        query.setString(2, batch.toString());
                        try (var rows = query.executeQuery()) {
                            if (!rows.next()) throw new IllegalStateException("Current start required for batch labor acquisition");
                        }
                    }
                    replaceOwner(building, null, batch, expectedRevision);
                    try (var statement = connection.prepareStatement("INSERT INTO building_staffing_roles(building_id,occupation_id,occupied_count) VALUES(?,?,?)")) {
                        for (var worker : allocation.occupiedByOccupation().entrySet()) {
                            statement.setString(1, building.toString());
                            statement.setString(2, worker.getKey());
                            statement.setInt(3, worker.getValue());
                            statement.executeUpdate();
                        }
                    }
                    return null;
                }); } catch (SQLException failure) { throw new IllegalStateException("Cannot acquire batch labor", failure); }
            }

            @Override public void release(UUID building, UUID batch, long expectedRevision) {
                try { tx.perform(() -> {
                    replaceOwner(building, Objects.requireNonNull(batch), null, expectedRevision);
                    try (var statement = connection.prepareStatement("DELETE FROM building_staffing_roles WHERE building_id=?")) {
                        statement.setString(1, building.toString());
                        if (statement.executeUpdate() == 0) throw new IllegalStateException("Batch labor is missing");
                    }
                    return null;
                }); } catch (SQLException failure) { throw new IllegalStateException("Cannot release batch labor", failure); }
            }
        };
    }

    private void replaceOwner(UUID building, UUID from, UUID to, long revision) throws SQLException {
        String sql = "UPDATE building_staffing SET batch_id=?,revision=? WHERE building_id=? AND batch_id IS ? AND revision=?";
        if (from != null) sql += """
                 AND EXISTS (SELECT 1 FROM production_executions e
                     JOIN production_batches b ON b.batch_id=e.current_batch_id
                     WHERE e.building_id=building_staffing.building_id AND b.batch_id=building_staffing.batch_id
                         AND b.status IN ('SETTLING_COMPLETED','SETTLING_ABORTED'))
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, to == null ? null : to.toString());
            statement.setLong(2, Math.incrementExact(revision));
            statement.setString(3, building.toString());
            statement.setString(4, from == null ? null : from.toString());
            statement.setLong(5, revision);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Batch labor owner or revision changed");
        }
    }

    private BuildingStaffing read(UUID id) {
        try (var statement = connection.prepareStatement("""
                SELECT s.batch_id,s.revision,r.occupation_id,r.occupied_count
                FROM building_staffing s LEFT JOIN building_staffing_roles r ON r.building_id=s.building_id
                WHERE s.building_id=?
                """)) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Staffing missing: " + id);
                String batch = rows.getString(1);
                long revision = rows.getLong(2);
                var occupied = new TreeMap<String, Integer>();
                do {
                    if (rows.getString(3) != null) occupied.put(rows.getString(3), Math.toIntExact(rows.getLong(4)));
                } while (rows.next());
                return new BuildingStaffing(id, batch == null ? null : UUID.fromString(batch), occupied, revision);
            }
        } catch (SQLException failure) { throw new IllegalStateException("Cannot read batch labor", failure); }
    }
}
