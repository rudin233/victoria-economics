package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** File opening and compatibility gate, independent of Minecraft so the real startup path is testable. */
public final class SqliteEconomyDatabase {
    private SqliteEconomyDatabase() {}

    public static Connection open(Path file) {
        Connection connection = null;
        try {
            boolean existing = Files.exists(file);
            if (existing) {
                // Physical read-only mode, not merely PRAGMA query_only on a writable connection.
                // Refuse old/unknown/corrupt files before opening any writable SQLite handle.
                var config = new SQLiteConfig();
                config.setReadOnly(true);
                try (var readOnly = SqliteConnectionGuard.protect(
                        DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), config.toProperties()))) {
                    EconomySchema.validateExisting(readOnly);
                }
            }
            var writable = new SQLiteConfig();
            if (existing) writable.resetOpenMode(SQLiteOpenMode.CREATE);
            connection = SqliteConnectionGuard.protect(
                    DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), writable.toProperties()));
            EconomySchema.initialize(connection, !existing); // Recheck; never rebuild an existing file.
            return connection;
        } catch (SQLException | RuntimeException failure) {
            if (connection != null) try { connection.close(); }
            catch (SQLException close) { failure.addSuppressed(close); }
            throw new IllegalStateException("Cannot open Victoria Economics database; use a new world if incompatible. No migration was attempted: " + failure.getMessage(), failure);
        }
    }
}
