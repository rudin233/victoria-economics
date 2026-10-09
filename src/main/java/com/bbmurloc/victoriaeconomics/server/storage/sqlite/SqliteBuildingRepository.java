package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionBatch;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionRecipeResolver;
import java.sql.*;
import java.util.*;

/**
 * A building checkpoint atomically includes effective/pending methods, execution and batch history.
 */
public final class SqliteBuildingRepository implements BuildingRepository {
    private final Connection connection;
    private final ProductionRecipeResolver recipes;
    private final Object lock;
    private final ProductionStateCodec codec = new ProductionStateCodec();

    public SqliteBuildingRepository(Connection connection, ProductionRecipeResolver recipes, Object lock) {
        this.connection = connection;
        this.recipes = recipes;
        this.lock = lock;
    }

    @Override
    public void save(EconomicBuilding building) {
        synchronized (lock) {
            try {
                boolean auto = connection.getAutoCommit();
                Savepoint point = null;
                if (auto) connection.setAutoCommit(false);
                else point = connection.setSavepoint();
                try {
                    try (var statement = connection.prepareStatement("INSERT INTO economic_buildings(id, building_type_id, status) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET building_type_id=excluded.building_type_id, status=excluded.status")) {
                        statement.setString(1, building.getId().toString());
                        statement.setString(2, building.getBuildingTypeId());
                        statement.setString(3, building.getStatus().name());
                        statement.executeUpdate();
                    }
                    var department = building.getProductionDepartment();
                    saveSelections("building_production_method_selections", building.getId(), department.getSelectedProductionMethods());
                    saveSelections("building_pending_production_methods", building.getId(), department.getPendingProductionMethods().map(p -> p.methods()).orElse(Map.of()));
                    try (var statement = connection.prepareStatement("INSERT INTO building_production_execution(building_id, state_json) VALUES (?, ?) ON CONFLICT(building_id) DO UPDATE SET state_json=excluded.state_json")) {
                        statement.setString(1, building.getId().toString());
                        statement.setString(2, codec.encode(department.getExecution()));
                        statement.executeUpdate();
                    }
                    saveBatch(department.getExecution().batch());
                    saveBatch(department.getExecution().lastBatch());
                    if (auto) connection.commit();
                    else connection.releaseSavepoint(point);
                } catch (SQLException | RuntimeException failure) {
                    if (auto) connection.rollback();
                    else connection.rollback(point);
                    throw failure;
                } finally {
                    if (auto) connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to checkpoint building: " + building.getId(), e);
            }
        }
    }

    private void saveBatch(ProductionBatch batch) throws SQLException {
        if (batch == null) return;
        try (var statement = connection.prepareStatement("INSERT INTO production_batches(batch_id, building_id, state_json) VALUES (?, ?, ?) ON CONFLICT(batch_id) DO UPDATE SET state_json=excluded.state_json")) {
            statement.setString(1, batch.getId().toString());
            statement.setString(2, batch.getBuildingId().toString());
            statement.setString(3, codec.encodeBatch(batch));
            statement.executeUpdate();
        }
    }

    private void saveSelections(String table, UUID building, Map<String, String> selections) throws SQLException {
        try (var statement = connection.prepareStatement("DELETE FROM " + table + " WHERE building_id = ?")) {
            statement.setString(1, building.toString());
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("INSERT INTO " + table + "(building_id, group_id, method_id) VALUES (?, ?, ?)")) {
            for (var entry : selections.entrySet()) {
                statement.setString(1, building.toString());
                statement.setString(2, entry.getKey());
                statement.setString(3, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    @Override
    public List<EconomicBuilding> loadAll() {
        synchronized (lock) {
            List<EconomicBuilding> result = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT id, building_type_id, status FROM economic_buildings"); var rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID id = UUID.fromString(rows.getString(1));
                    String type = rows.getString(2);
                    EconomicBuilding building = new EconomicBuilding(id, type);
                    building.setStatus(BuildingStatus.valueOf(rows.getString(3)));
                    Map<String, String> effective = loadSelections("building_production_method_selections", id);
                    Map<String, String> pending = loadSelections("building_pending_production_methods", id);
                    building.getProductionDepartment().initializeMethods(recipes.rulesFor(type), effective, pending.isEmpty() ? null : pending);
                    try (var execution = connection.prepareStatement("SELECT state_json FROM building_production_execution WHERE building_id = ?")) {
                        execution.setString(1, id.toString());
                        try (var saved = execution.executeQuery()) {
                            if (saved.next())
                                codec.restore(saved.getString(1), building.getProductionDepartment().getExecution());
                        }
                    }
                    result.add(building);
                }
                return List.copyOf(result);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to restore buildings", e);
            }
        }
    }

    private Map<String, String> loadSelections(String table, UUID building) throws SQLException {
        Map<String, String> result = new HashMap<>();
        try (var statement = connection.prepareStatement("SELECT group_id, method_id FROM " + table + " WHERE building_id = ?")) {
            statement.setString(1, building.toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.put(rows.getString(1), rows.getString(2));
            }
        }
        return Map.copyOf(result);
    }

    @Override
    public void delete(UUID buildingId) {
        // Deletion of an economic building needs a separate asset/liability closeout process.
        throw new UnsupportedOperationException("Building deletion requires inventory, employment and liability closeout");
    }
}
