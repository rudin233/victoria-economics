package com.bbmurloc.victoriaeconomics.server.inventory.infrastructure;

import com.bbmurloc.victoriaeconomics.server.production.port.ProductionInventoryPort;
import com.bbmurloc.victoriaeconomics.server.inventory.domain.GoodsInventory;
import com.google.gson.Gson;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/**
 * Reads a location, applies its domain behavior, then atomically replaces its durable state.
 */
public final class SqliteInventoryStore implements ProductionInventoryPort {
    private final Connection connection;
    private final Object lock;
    private final Gson gson = new Gson();

    public SqliteInventoryStore(Connection connection, Object lock) {
        this.connection = connection;
        this.lock = lock;
    }

    public void createLocation(UUID location, double capacity) {
        synchronized (lock) {
            GoodsInventory inventory = new GoodsInventory(location, capacity);
            try (var statement = connection.prepareStatement("INSERT INTO inventory_locations(location_id, state_json) VALUES(?, ?)")) {
                statement.setString(1, location.toString());
                statement.setString(2, gson.toJson(inventory.state()));
                statement.executeUpdate();
            } catch (SQLException e) {
                throw failure(location, e);
            }
        }
    }

    public void deposit(UUID location, Map<String, Double> amounts) {
        mutate(location, i -> {
            i.deposit(amounts);
            return null;
        });
    }

    public void withdraw(UUID location, Map<String, Double> amounts) {
        mutate(location, i -> {
            i.withdraw(amounts);
            return null;
        });
    }

    @Override
    public GoodsInventory.State inspect(UUID location) {
        synchronized (lock) {
            return load(location).state();
        }
    }

    @Override
    public void reserve(UUID location, UUID batch, Map<String, Double> inputs) {
        mutate(location, i -> {
            i.reserve(batch, inputs);
            return null;
        });
    }

    @Override
    public boolean isReserved(UUID location, UUID batch, Map<String, Double> inputs) {
        synchronized (lock) {
            return load(location).hasReservation(batch, inputs);
        }
    }

    @Override
    public void release(UUID location, UUID batch) {
        mutate(location, i -> {
            i.release(batch);
            return null;
        });
    }

    @Override
    public GoodsInventory.Settlement settle(UUID location, UUID batch, Map<String, Double> inputs, Map<String, Double> outputs, double progress) {
        return mutate(location, i -> i.settle(batch, inputs, outputs, progress));
    }

    private GoodsInventory load(UUID location) {
        try (var statement = connection.prepareStatement("SELECT state_json FROM inventory_locations WHERE location_id = ?")) {
            statement.setString(1, location.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next())
                    throw new IllegalStateException("Storage location has not been configured: " + location);
                return new GoodsInventory(gson.fromJson(rows.getString(1), GoodsInventory.State.class));
            }
        } catch (SQLException e) {
            throw failure(location, e);
        }
    }

    private <T> T mutate(UUID location, Function<GoodsInventory, T> operation) {
        synchronized (lock) {
            GoodsInventory inventory = load(location);
            T result = operation.apply(inventory);
            try (var statement = connection.prepareStatement("UPDATE inventory_locations SET state_json = ? WHERE location_id = ?")) {
                statement.setString(1, gson.toJson(inventory.state()));
                statement.setString(2, location.toString());
                if (statement.executeUpdate() != 1) throw new SQLException("Storage location disappeared");
                return result;
            } catch (SQLException e) {
                throw failure(location, e);
            }
        }
    }

    private RuntimeException failure(UUID location, SQLException e) {
        return new IllegalStateException("Inventory persistence failed at " + location, e);
    }
}
