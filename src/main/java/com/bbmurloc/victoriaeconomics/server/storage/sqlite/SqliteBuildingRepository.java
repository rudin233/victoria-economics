package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.building.*;
import java.sql.*;
import java.util.*;

/** Persists Building Identity only. Production writes never pass through this repository. */
public final class SqliteBuildingRepository implements BuildingRepository {
    private final Connection connection;
    private final Object lock;
    public SqliteBuildingRepository(Connection connection, Object lock) {
        this.connection = connection;
        this.lock = lock;
    }
    @Override public void save(EconomicBuilding building) {
        synchronized (lock) {
            SqliteTransactions.run(connection, () -> {
                try (var statement = connection.prepareStatement("INSERT INTO economic_buildings(id,building_type_id,status) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status")) {
                    statement.setString(1, building.getId().toString());
                    statement.setString(2, building.getBuildingTypeId());
                    statement.setString(3, building.getStatus().name());
                    statement.executeUpdate();
                }
                return null;
            });
        }
    }
    @Override public List<EconomicBuilding> loadAll() {
        synchronized (lock) {
            try (var statement = connection.prepareStatement("SELECT id,building_type_id,status FROM economic_buildings"); var rows = statement.executeQuery()) {
                List<EconomicBuilding> result = new ArrayList<>();
                while (rows.next()) {
                    var building = new EconomicBuilding(UUID.fromString(rows.getString(1)), rows.getString(2));
                    building.setStatus(BuildingStatus.valueOf(rows.getString(3)));
                    result.add(building);
                }
                return List.copyOf(result);
            } catch (SQLException failure) { throw new IllegalStateException("Cannot restore building identities", failure); }
        }
    }
    @Override public void delete(UUID id) {
        throw new UnsupportedOperationException("Building deletion requires inventory, employment and liability closeout");
    }
}
