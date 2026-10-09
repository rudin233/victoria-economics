package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import java.sql.*;

/**
 * Additive, versioned migrations. Never drops or truncates existing world data.
 */
public final class EconomySchema {
    private EconomySchema() {
    }

    public static void initialize(Connection connection) {
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS economy_schema_migrations(version INTEGER PRIMARY KEY)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS economic_buildings(id TEXT PRIMARY KEY, building_type_id TEXT NOT NULL, status TEXT NOT NULL, current_equipment INTEGER NOT NULL DEFAULT 0)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS building_production_method_selections(building_id TEXT NOT NULL, group_id TEXT NOT NULL, method_id TEXT NOT NULL, PRIMARY KEY(building_id, group_id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS building_pending_production_methods(building_id TEXT NOT NULL, group_id TEXT NOT NULL, method_id TEXT NOT NULL, PRIMARY KEY(building_id, group_id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS building_production_execution(building_id TEXT PRIMARY KEY, state_json TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS production_batches(batch_id TEXT PRIMARY KEY, building_id TEXT NOT NULL, state_json TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS production_start_intents(batch_id TEXT PRIMARY KEY, building_id TEXT NOT NULL UNIQUE)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS inventory_locations(location_id TEXT PRIMARY KEY, state_json TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS equipment_holdings(building_id TEXT PRIMARY KEY, state_json TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS employment_state(id INTEGER PRIMARY KEY CHECK(id = 1), state_json TEXT NOT NULL)");
            statement.executeUpdate("INSERT OR IGNORE INTO economy_schema_migrations(version) VALUES (1)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to migrate economy schema", e);
        }
    }
}
