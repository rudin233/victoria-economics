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

    private final Path databasePath;
    private final Connection connection;

    private EconomyDatabase(Path databasePath, Connection connection) {
        this.databasePath = databasePath;
        this.connection = connection;
    }

    public static EconomyDatabase open(MinecraftServer server) {
        try {
            Path worldRoot = server.getWorldPath(LevelResource.ROOT);

            Path storageDirectory =
                    worldRoot.resolve("victoria_economics");

            Files.createDirectories(storageDirectory);

            Path databasePath =
                    storageDirectory.resolve("economy.sqlite");

            Connection connection =
                    DriverManager.getConnection(
                            "jdbc:sqlite:" + databasePath.toAbsolutePath()
                    );

            initializeSchema(connection);

            return new EconomyDatabase(databasePath, connection);

        } catch (IOException | SQLException exception) {
            throw new IllegalStateException(
                    "Failed to open Victoria Economics database",
                    exception
            );
        }
    }

    private static void initializeSchema(Connection connection)
            throws SQLException {

        String createBuildingsTable = """
                CREATE TABLE IF NOT EXISTS economic_buildings (
                    id TEXT PRIMARY KEY,
                    building_type_id TEXT NOT NULL,
                    status TEXT NOT NULL
                )
                """;

        try (Statement statement = connection.createStatement()) {
            statement.execute(createBuildingsTable);
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    @Override
    public void close() {
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed to close Victoria Economics database",
                    exception
            );
        }
    }
}