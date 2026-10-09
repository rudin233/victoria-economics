package com.bbmurloc.victoriaeconomics.server.storage;

import com.bbmurloc.victoriaeconomics.server.storage.sqlite.EconomySchema;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;

public final class EconomyDatabase implements AutoCloseable {
    private final Connection connection;

    private EconomyDatabase(Connection connection) {
        this.connection = connection;
    }

    public static EconomyDatabase open(MinecraftServer server) {
        return open(server.getWorldPath(LevelResource.ROOT));
    }

    public static EconomyDatabase open(Path worldRoot) {
        Connection connection = null;
        try {
            Path directory = worldRoot.resolve("victoria_economics");
            Files.createDirectories(directory);
            connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("economy.sqlite").toAbsolutePath());
            EconomySchema.initialize(connection);
            return new EconomyDatabase(connection);
        } catch (IOException | SQLException | RuntimeException failure) {
            if (connection != null) try {
                connection.close();
            } catch (SQLException close) {
                failure.addSuppressed(close);
            }
            throw new IllegalStateException("Failed to open Victoria Economics database", failure);
        }
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to close economy database", e);
        }
    }
}
