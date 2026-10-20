package com.bbmurloc.victoriaeconomics.server.storage;

import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteEconomyDatabase;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;

public final class EconomyDatabase implements AutoCloseable {
    private final Connection connection;
    private final Path file;

    private EconomyDatabase(Connection connection, Path file) {
        this.connection = connection;
        this.file = file;
    }

    public static EconomyDatabase open(MinecraftServer server) {
        return open(server.getWorldPath(LevelResource.ROOT));
    }

    public static EconomyDatabase open(Path worldRoot) {
        Connection connection = null;
        try {
            Path directory = worldRoot.resolve("victoria_economics");
            Files.createDirectories(directory);
            connection = SqliteEconomyDatabase.open(directory.resolve("economy.sqlite"));
            return new EconomyDatabase(connection, directory.resolve("economy.sqlite"));
        } catch (IOException | RuntimeException failure) {
            if (connection != null) try {
                connection.close();
            } catch (SQLException close) {
                failure.addSuppressed(close);
            }
            throw new IllegalStateException("Failed to open Victoria Economics database: " + failure.getMessage(), failure);
        }
    }

    public Connection getConnection() {
        return connection;
    }
    public Connection openVerificationConnection() { return SqliteEconomyDatabase.open(file); }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to close economy database", e);
        }
    }
}
