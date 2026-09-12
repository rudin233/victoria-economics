package com.bbmurloc.victoriaeconomics.server.storage;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class EconomyDatabase implements AutoCloseable {

    private final Connection connection;

    private EconomyDatabase(Connection connection) {
        this.connection = connection;
    }

    public static EconomyDatabase open(MinecraftServer server) {
        try {
            Path worldRoot =
                    server.getWorldPath(LevelResource.ROOT);

            Path economyDirectory =
                    worldRoot.resolve("victoria_economics");

            Files.createDirectories(economyDirectory);

            Path databasePath =
                    economyDirectory.resolve("economy.sqlite");

            Connection connection =
                    DriverManager.getConnection(
                            "jdbc:sqlite:"
                                    + databasePath.toAbsolutePath()
                    );

            EconomyDatabase database =
                    new EconomyDatabase(connection);

            database.initializeSchema();

            return database;

        } catch (IOException | SQLException e) {
            throw new RuntimeException(
                    "Failed to open Victoria Economics database",
                    e
            );
        }
    }

    private void initializeSchema() {
        try (Statement statement =
                     connection.createStatement()) {

            /*
             * EconomicBuilding 的主要持久状态。
             */
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS economic_buildings (
                        id TEXT PRIMARY KEY,
                        building_type_id TEXT NOT NULL,
                        status TEXT NOT NULL,
                        current_equipment INTEGER NOT NULL DEFAULT 0
                    )
                    """);

            /*
             * 每座建筑在每个 Production Method Group
             * 中当前选择的 Production Method。
             */
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS building_production_method_selections (
                        building_id TEXT NOT NULL,
                        group_id TEXT NOT NULL,
                        method_id TEXT NOT NULL,
                        PRIMARY KEY (building_id, group_id)
                    )
                    """);

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to initialize Victoria Economics database schema",
                    e
            );
        }
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public void close() {
        try {
            if (!connection.isClosed()) {
                connection.close();
            }

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to close Victoria Economics database",
                    e
            );
        }
    }
}