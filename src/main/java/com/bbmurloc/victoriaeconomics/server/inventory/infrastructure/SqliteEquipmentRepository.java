package com.bbmurloc.victoriaeconomics.server.inventory.infrastructure;

import com.bbmurloc.victoriaeconomics.server.inventory.domain.ProductionEquipmentHolding;
import com.google.gson.Gson;
import java.sql.*;
import java.util.*;

public final class SqliteEquipmentRepository {
    private final Connection connection;
    private final Gson gson = new Gson();

    public SqliteEquipmentRepository(Connection connection) {
        this.connection = connection;
    }

    public void save(ProductionEquipmentHolding holding) {
        saveAll(List.of(holding));
    }

    public void saveAll(List<ProductionEquipmentHolding> holdings) {
        try {
            boolean auto = connection.getAutoCommit();
            Savepoint point = null;
            if (auto) connection.setAutoCommit(false);
            else point = connection.setSavepoint();
            try (var statement = connection.prepareStatement("INSERT INTO equipment_holdings(building_id, state_json) VALUES (?, ?) ON CONFLICT(building_id) DO UPDATE SET state_json=excluded.state_json")) {
                for (var holding : holdings) {
                    statement.setString(1, holding.getBuildingId().toString());
                    statement.setString(2, gson.toJson(holding.state()));
                    statement.addBatch();
                }
                statement.executeBatch();
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
            throw new IllegalStateException("Failed to save equipment locations", e);
        }
    }

    public List<ProductionEquipmentHolding> loadAll() {
        List<ProductionEquipmentHolding> result = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT state_json FROM equipment_holdings"); var rows = statement.executeQuery()) {
            while (rows.next())
                result.add(new ProductionEquipmentHolding(gson.fromJson(rows.getString(1), ProductionEquipmentHolding.State.class)));
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to restore equipment", e);
        }
    }
}
