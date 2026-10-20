package com.bbmurloc.victoriaeconomics.server.inventory.infrastructure;

import com.bbmurloc.victoriaeconomics.server.production.port.ProductionInventoryPort;
import com.bbmurloc.victoriaeconomics.server.inventory.domain.GoodsInventory;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.EconomyStateCodec;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteTransactions;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionSettlement;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/**
 * Reads a location, applies its domain behavior, then atomically replaces its durable state.
 */
public final class SqliteInventoryStore implements ProductionInventoryPort {
    private final Connection connection;
    private final Object lock;

    public SqliteInventoryStore(Connection connection, Object lock) {
        this.connection = connection;
        this.lock = lock;
    }

    public void createLocation(UUID location, long capacity) {
        synchronized (lock) {
            GoodsInventory inventory = new GoodsInventory(location, capacity);
            SqliteTransactions.write(connection, session -> {
                session.requireConnection(connection);
                try (var statement = connection.prepareStatement("INSERT INTO inventory_locations(location_id, state_json, revision) VALUES(?, ?, 0)")) {
                    statement.setString(1, location.toString());
                    statement.setString(2, EconomyStateCodec.encodeInventory(inventory.state()));
                    statement.executeUpdate();
                }
                return null;
            });
        }
    }

    public void deposit(UUID location, Map<String, Long> amounts) {
        mutate(location, i -> {
            i.deposit(amounts);
            return null;
        });
    }

    public void withdraw(UUID location, Map<String, Long> amounts) {
        mutate(location, i -> {
            i.withdraw(amounts);
            return null;
        });
    }

    @Override
    public StorageStatus storageStatus(UUID location) {
        synchronized (lock) {
            return new StorageStatus(load(location).overCapacity());
        }
    }

    /** Inventory diagnostics; the production port exposes only storageStatus. */
    public GoodsInventory.State inspect(UUID location) {
        synchronized (lock) {
            return load(location).state();
        }
    }

    @Override
    public void reserve(UUID location, UUID batch, Map<String, Long> inputs) {
        mutate(location, i -> {
            i.reserve(batch, inputs);
            return null;
        });
    }

    @Override
    public boolean isReserved(UUID location, UUID batch, Map<String, Long> inputs) {
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
    public SettlementReceipt settle(UUID location, ProductionSettlement command) {
        var settlement = mutate(location, i -> i.settle(command));
        return new SettlementReceipt(settlement.command());
    }

    private GoodsInventory load(UUID location) {
        SqliteTransactions.requireCommittedReads(connection);
        return read(location).inventory();
    }

    private record Loaded(GoodsInventory inventory, long revision) {}

    public boolean configured(SqliteTransactions.WriteSession tx,UUID location){
        tx.requireConnection(connection);
        try(var s=connection.prepareStatement("SELECT 1 FROM inventory_locations WHERE location_id=?")){
            s.setString(1,location.toString());try(var r=s.executeQuery()){return r.next();}
        }catch(SQLException failure){throw failure(location,failure);}
    }

    public boolean inputsAvailable(SqliteTransactions.WriteSession tx,UUID location,Map<String,Long> inputs){
        tx.requireConnection(connection);var inventory=read(location).inventory();
        return inputs.entrySet().stream().allMatch(entry->inventory.available(entry.getKey())>=entry.getValue());
    }

    /** Explicit read capability for final start checks while the caller owns the SQLite write lock. */
    public ProductionInventoryPort.ReadSession inTransaction(SqliteTransactions.WriteSession tx) {
        tx.requireConnection(connection);
        return new ProductionInventoryPort.ReadSession(){
            private GoodsInventory current(UUID id){tx.requireConnection(connection);return read(id).inventory();}
            public StorageStatus storageStatus(UUID id){return new StorageStatus(current(id).overCapacity());}
            public boolean isReserved(UUID id,UUID batch,Map<String,Long> inputs){return current(id).hasReservation(batch,inputs);}
        };
    }

    private Loaded read(UUID location) {
        try (var statement = connection.prepareStatement("SELECT state_json, revision, typeof(revision) AS revision_type FROM inventory_locations WHERE location_id = ?")) {
            statement.setString(1, location.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next())
                    throw new IllegalStateException("Storage location has not been configured: " + location);
                if (!rows.getString("revision_type").equals("integer") || rows.getLong("revision") < 0)
                    throw new IllegalStateException("Invalid inventory revision");
                return new Loaded(EconomyStateCodec.decodeInventory(location, rows.getString(1)), rows.getLong("revision"));
            }
        } catch (SQLException e) {
            throw failure(location, e);
        }
    }

    private <T> T mutate(UUID location, Function<GoodsInventory, T> operation) {
        synchronized (lock) {
            return SqliteTransactions.write(connection, session -> {
                session.requireConnection(connection);
                Loaded loaded = read(location); // Authoritative re-read only after BEGIN IMMEDIATE.
                GoodsInventory inventory = loaded.inventory();
                var before = inventory.state();
                T result = operation.apply(inventory);
                if (before.equals(inventory.state())) return result;
                try (var statement = connection.prepareStatement("UPDATE inventory_locations SET state_json = ?, revision = ? WHERE location_id = ? AND revision = ?")) {
                    statement.setString(1, EconomyStateCodec.encodeInventory(inventory.state()));
                    statement.setLong(2, Math.incrementExact(loaded.revision()));
                    statement.setString(3, location.toString());
                    statement.setLong(4, loaded.revision());
                    if (statement.executeUpdate() != 1) throw new IllegalStateException("Inventory revision conflict or missing location: " + location);
                    return result;
                } catch (SQLException e) {
                    throw failure(location, e);
                }
            });
        }
    }

    private RuntimeException failure(UUID location, SQLException e) {
        return new IllegalStateException("Inventory persistence failed at " + location, e);
    }
}
