package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import java.sql.*;
import java.util.*;

/** Fresh B-prime schema. Existing incompatible worlds are rejected, never converted or cleared. */
public final class EconomySchema {
    public static final int VERSION = 2;
    private EconomySchema() {}

    private static final Map<String, Set<String>> COLUMNS = Map.of(
            "economy_schema_migrations", Set.of("version"),
            "economic_buildings", Set.of("id", "building_type_id", "status"),
            "production_method_configurations", Set.of("building_id", "effective_json", "pending_json", "configuration_revision", "effective_revision"),
            "production_executions", Set.of("building_id", "current_batch_id", "last_batch_id", "boundary", "automatic", "execution_revision", "methods_effective_revision"),
            "production_batches", Set.of("batch_id", "building_id", "configuration_json", "progress_units", "status"),
            "production_start_intents", Set.of("batch_id", "building_id"),
            "inventory_locations", Set.of("location_id", "state_json"),
            "equipment_holdings", Set.of("building_id", "state_json"),
            "employment_state", Set.of("id", "state_json"));

    public static void initialize(Connection connection) {
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            Set<String> tables = new HashSet<>();
            try (var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                while (rows.next()) tables.add(rows.getString(1));
            }
            if (!tables.isEmpty()) {
                if (!tables.equals(COLUMNS.keySet())) throw incompatible();
                try (var rows = statement.executeQuery("SELECT version FROM economy_schema_migrations")) {
                    if (!rows.next() || rows.getInt(1) != VERSION || rows.next()) throw incompatible();
                }
                for (var table : COLUMNS.entrySet()) {
                    Set<String> columns = new HashSet<>();
                    try (var rows = statement.executeQuery("PRAGMA table_info('" + table.getKey() + "')")) {
                        while (rows.next()) columns.add(rows.getString("name"));
                    }
                    if (!columns.equals(table.getValue())) throw incompatible();
                }
                return;
            }
            SqliteTransactions.run(connection, () -> {
                statement.executeUpdate("CREATE TABLE economy_schema_migrations(version INTEGER PRIMARY KEY)");
                statement.executeUpdate("CREATE TABLE economic_buildings(id TEXT PRIMARY KEY, building_type_id TEXT NOT NULL, status TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE production_method_configurations(building_id TEXT PRIMARY KEY REFERENCES economic_buildings(id), effective_json TEXT NOT NULL, pending_json TEXT, configuration_revision INTEGER NOT NULL CHECK(configuration_revision >= 0), effective_revision INTEGER NOT NULL CHECK(effective_revision >= 0 AND effective_revision <= configuration_revision))");
                statement.executeUpdate("CREATE TABLE production_batches(batch_id TEXT PRIMARY KEY, building_id TEXT NOT NULL REFERENCES economic_buildings(id), configuration_json TEXT NOT NULL, progress_units INTEGER NOT NULL CHECK(progress_units >= 0), status TEXT NOT NULL CHECK(status IN ('ACTIVE','PAUSED','SETTLING_COMPLETED','SETTLING_ABORTED','COMPLETED','ABORTED')), UNIQUE(batch_id, building_id))");
                statement.executeUpdate("CREATE UNIQUE INDEX one_unfinished_batch_per_building ON production_batches(building_id) WHERE status IN ('ACTIVE','PAUSED','SETTLING_COMPLETED','SETTLING_ABORTED')");
                statement.executeUpdate("CREATE TABLE production_executions(building_id TEXT PRIMARY KEY REFERENCES economic_buildings(id), current_batch_id TEXT, last_batch_id TEXT, boundary TEXT NOT NULL CHECK(boundary IN ('NONE','METHODS','EQUIPMENT','WORKFORCE')), automatic INTEGER NOT NULL CHECK(automatic IN (0,1)), execution_revision INTEGER NOT NULL CHECK(execution_revision >= 0), methods_effective_revision INTEGER NOT NULL CHECK(methods_effective_revision >= 0), CHECK(current_batch_id IS NULL OR last_batch_id IS NULL OR current_batch_id <> last_batch_id), FOREIGN KEY(current_batch_id, building_id) REFERENCES production_batches(batch_id, building_id) DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(last_batch_id, building_id) REFERENCES production_batches(batch_id, building_id) DEFERRABLE INITIALLY DEFERRED)");
                statement.executeUpdate("CREATE TABLE production_start_intents(batch_id TEXT PRIMARY KEY, building_id TEXT NOT NULL UNIQUE REFERENCES economic_buildings(id))");
                statement.executeUpdate("CREATE TABLE inventory_locations(location_id TEXT PRIMARY KEY, state_json TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE equipment_holdings(building_id TEXT PRIMARY KEY, state_json TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE employment_state(id INTEGER PRIMARY KEY CHECK(id=1), state_json TEXT NOT NULL)");
                statement.executeUpdate("INSERT INTO economy_schema_migrations(version) VALUES (" + VERSION + ")");
                return null;
            });
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot initialize Victoria Economics schema; use a new world", failure);
        }
    }

    private static IllegalStateException incompatible() {
        return new IllegalStateException("Incompatible Victoria Economics database. ADR-PO-02 requires a new world; existing data was not changed.");
    }
}
