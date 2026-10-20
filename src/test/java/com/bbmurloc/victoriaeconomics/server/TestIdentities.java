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
            long version;
            try(var r=statement.executeQuery("SELECT version FROM economy_schema_migrations")){r.next();version=r.getLong(1);}
            if(version>=7){
                statement.executeUpdate("INSERT INTO currencies VALUES('00000000-0000-02bc-0000-000000000001','Explicit isolated test currency') ON CONFLICT DO NOTHING");
                statement.executeUpdate("INSERT INTO regional_wage_rates VALUES('"+REGION+"','00000000-0000-02bc-0000-000000000001',0,1,0) ON CONFLICT DO NOTHING");
            }
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
        long version;
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT version FROM economy_schema_migrations")){r.next();version=r.getLong(1);}
        if(version>=7){
            UUID segment=UUID.nameUUIDFromBytes(("payroll:"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try(var s=connection.createStatement()){
                s.executeUpdate("INSERT INTO payroll_accruals VALUES('"+segment+"','"+id+"','"+COMPANY+"','"+REGION+"','00000000-0000-02bc-0000-000000000001',0,0,NULL,0,0,0,0,0,0,0)");
                s.executeUpdate("INSERT INTO payroll_employment_history VALUES('"+segment+"',0,0,0)");
            }
        }
    }
}
