package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import java.sql.*;
import java.util.*;

/** Fresh B-prime schema. Existing incompatible worlds are rejected, never converted or cleared. */
public final class EconomySchema {
    public static final int VERSION = 4;
    private EconomySchema() {}

    // v4 removes dedicated equipment persistence and snapshots; NPC UUID employment remains.
    // Creation and compatibility use the same DDL, with no legacy migration.
    private static final List<String> DDL = List.of(
            "CREATE TABLE economy_schema_migrations(version INTEGER PRIMARY KEY)",
            "CREATE TABLE economic_buildings(id TEXT PRIMARY KEY, building_type_id TEXT NOT NULL, status TEXT NOT NULL)",
            "CREATE TABLE production_method_configurations(building_id TEXT PRIMARY KEY REFERENCES economic_buildings(id), effective_json TEXT NOT NULL, pending_json TEXT, configuration_revision INTEGER NOT NULL CHECK(configuration_revision >= 0), effective_revision INTEGER NOT NULL CHECK(effective_revision >= 0 AND effective_revision <= configuration_revision))",
            "CREATE TABLE production_batches(batch_id TEXT PRIMARY KEY, building_id TEXT NOT NULL REFERENCES economic_buildings(id), configuration_json TEXT NOT NULL, progress_units INTEGER NOT NULL CHECK(progress_units >= 0), status TEXT NOT NULL CHECK(status IN ('ACTIVE','PAUSED','SETTLING_COMPLETED','SETTLING_ABORTED','COMPLETED','ABORTED')), UNIQUE(batch_id, building_id))",
            "CREATE UNIQUE INDEX one_unfinished_batch_per_building ON production_batches(building_id) WHERE status IN ('ACTIVE','PAUSED','SETTLING_COMPLETED','SETTLING_ABORTED')",
            "CREATE TABLE production_executions(building_id TEXT PRIMARY KEY REFERENCES economic_buildings(id), current_batch_id TEXT, last_batch_id TEXT, boundary TEXT NOT NULL CHECK(boundary IN ('NONE','METHODS','EQUIPMENT','WORKFORCE')), automatic INTEGER NOT NULL CHECK(automatic IN (0,1)), execution_revision INTEGER NOT NULL CHECK(execution_revision >= 0), methods_effective_revision INTEGER NOT NULL CHECK(methods_effective_revision >= 0), CHECK(current_batch_id IS NULL OR last_batch_id IS NULL OR current_batch_id <> last_batch_id), FOREIGN KEY(current_batch_id, building_id) REFERENCES production_batches(batch_id, building_id) DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(last_batch_id, building_id) REFERENCES production_batches(batch_id, building_id) DEFERRABLE INITIALLY DEFERRED)",
            "CREATE TABLE production_start_intents(batch_id TEXT PRIMARY KEY, building_id TEXT NOT NULL UNIQUE REFERENCES economic_buildings(id))",
            "CREATE TABLE inventory_locations(location_id TEXT PRIMARY KEY, state_json TEXT NOT NULL, revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision >= 0))",
            "CREATE TABLE employment_state(id INTEGER PRIMARY KEY CHECK(id=1), state_json TEXT NOT NULL)");

    public static void initialize(Connection connection) {
        initialize(connection, true);
    }

    /** Existing files must never enter the creation path, even if they changed after preflight. */
    static void initialize(Connection connection, boolean allowCreation) {
        SqliteTransactions.requireCommittedReads(connection);
        EconomyStateCodec.requireCurrentFormat();
        try (var statement = connection.createStatement()) {
            if (!allowCreation || !definitions(connection).isEmpty()) validateExisting(connection);
            else SqliteTransactions.run(connection, () -> {
                for (String ddl : DDL) statement.executeUpdate(ddl);
                statement.executeUpdate("INSERT INTO economy_schema_migrations(version) VALUES (" + VERSION + ")");
                return null;
            });
            // Connection-local options only, after the existing database has passed its read gate.
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot initialize Victoria Economics schema; use a new world", failure);
        }
    }

    /** Pure validation of a single committed snapshot; works on a physically read-only connection. */
    public static void validateExisting(Connection connection) {
        SqliteTransactions.requireCommittedReads(connection);
        EconomyStateCodec.requireCurrentFormat();
        SqliteTransactions.run(connection, () -> {
            Set<String> expected = new HashSet<>();
            DDL.forEach(ddl -> expected.add(normalize(ddl)));
            if (!definitions(connection).equals(expected)) throw incompatible();
            try (var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT version, typeof(version) AS kind FROM economy_schema_migrations")) {
                    if (!rows.next() || !rows.getString("kind").equals("integer")) throw incompatible();
                    long actual = rows.getLong("version");
                    if (rows.next()) throw incompatible();
                    if (actual != VERSION)
                        throw new IllegalStateException("Unsupported Victoria Economics Schema v" + actual + "; expected v" + VERSION + ". Use a new world; existing data was not changed and no migration was attempted.");
                }
                try (var rows = statement.executeQuery("PRAGMA quick_check")) {
                    if (!rows.next() || !rows.getString(1).equals("ok") || rows.next()) throw incompatible();
                }
                try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                    if (rows.next()) throw incompatible();
                }
                try (var rows = statement.executeQuery("SELECT *, typeof(progress_units) AS progress_type FROM production_batches")) {
                    while (rows.next()) {
                        if (!rows.getString("progress_type").equals("integer")) throw incompatible();
                        EconomyStateCodec.decodeBatch(storedId(rows.getString("batch_id")), storedId(rows.getString("building_id")),
                                rows.getString("configuration_json"), rows.getLong("progress_units"), rows.getString("status"));
                    }
                }
                try (var rows = statement.executeQuery("SELECT location_id, state_json FROM inventory_locations")) {
                    while (rows.next()) EconomyStateCodec.decodeInventory(storedId(rows.getString(1)), rows.getString(2));
                }
            }
            return null;
        });
    }

    private static Set<String> definitions(Connection connection) throws SQLException {
        Set<String> result = new HashSet<>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*'")) {
            while (rows.next()) result.add(normalize(rows.getString(1)));
        }
        return result;
    }

    private static String normalize(String ddl) { return ddl == null ? "" : ddl.trim().replaceAll("\\s+", " "); }

    private static UUID storedId(String text) {
        UUID id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw incompatible();
        return id;
    }

    private static IllegalStateException incompatible() {
        return new IllegalStateException("Incompatible Victoria Economics database: expected Schema v" + VERSION + " and its exact snapshot format. Use a new world; existing data was not changed and no migration was attempted.");
    }
}
