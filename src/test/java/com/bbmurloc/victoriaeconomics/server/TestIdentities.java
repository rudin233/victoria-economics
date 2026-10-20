package com.bbmurloc.victoriaeconomics.server;

import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import java.sql.*;
import java.util.UUID;

/** Explicit persisted test identities, never defaults in production code. */
public final class TestIdentities {
    public static final CompanyId COMPANY = new CompanyId(new UUID(100, 1));
    public static final RegionId REGION = new RegionId(new UUID(200, 1));
    private TestIdentities() {}
    public static void seed(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO companies VALUES('" + COMPANY + "') ON CONFLICT DO NOTHING");
            statement.executeUpdate("INSERT INTO regions VALUES('" + REGION + "') ON CONFLICT DO NOTHING");
        }
    }
    public static void building(Connection connection, UUID id, String type) throws SQLException {
        seed(connection);
        try (var insert = connection.prepareStatement("INSERT INTO economic_buildings VALUES(?,?,'ACTIVE',?,?,'EXISTING',0)")) {
            insert.setString(1, id.toString()); insert.setString(2, type);
            insert.setString(3, COMPANY.toString()); insert.setString(4, REGION.toString()); insert.executeUpdate();
        }
        try (var insert = connection.prepareStatement("INSERT INTO building_staffing VALUES(?,0)")) {
            insert.setString(1, id.toString()); insert.executeUpdate();
        }
    }
}
