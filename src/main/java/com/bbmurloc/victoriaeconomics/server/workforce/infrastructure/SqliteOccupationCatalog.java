package com.bbmurloc.victoriaeconomics.server.workforce.infrastructure;

import com.bbmurloc.victoriaeconomics.common.definition.occupation.OccupationRegistry;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteTransactions;
import java.sql.Connection;

/** Registers definition identities only; never creates supply or employment. Called after the schema gate. */
public final class SqliteOccupationCatalog {
    private SqliteOccupationCatalog() {}
    public static void register(Connection connection, OccupationRegistry occupations) {
        SqliteTransactions.write(connection, session -> {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id FROM occupation_catalog")) {
                while (rows.next()) if (occupations.get(rows.getString(1)) == null)
                    throw new IllegalStateException("Persisted occupation is absent from current definitions; no automatic conversion is allowed");
            }
            try (var statement = connection.prepareStatement("INSERT INTO occupation_catalog(id) VALUES(?) ON CONFLICT(id) DO NOTHING")) {
                for (var occupation : occupations.getAll()) { statement.setString(1, occupation.id()); statement.executeUpdate(); }
            }
            return null;
        });
    }
}
