package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import java.sql.*;
import java.util.*;

/** Fresh world schema. Existing incompatible worlds are rejected, never converted or cleared. */
public final class EconomySchema {
    public static final int VERSION = 9;
    private EconomySchema() {}

    // v9 adds durable lifecycle quiescence and explicit unfinished-policy gates.
    // Creation and compatibility use the same DDL, with no legacy migration.
    private static final List<String> DDL = List.of(
            "CREATE TABLE economy_schema_migrations(version INTEGER PRIMARY KEY)",
            "CREATE TABLE companies(id TEXT PRIMARY KEY NOT NULL)",
            "CREATE TABLE regions(id TEXT PRIMARY KEY NOT NULL)",
            "CREATE TABLE occupation_catalog(id TEXT PRIMARY KEY NOT NULL CHECK(length(trim(id)) > 0))",
            "CREATE TABLE economic_buildings(id TEXT PRIMARY KEY NOT NULL, building_type_id TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('ACTIVE','STOPPED','DISABLED')), owner_company_id TEXT NOT NULL REFERENCES companies(id), region_id TEXT NOT NULL REFERENCES regions(id), lifecycle TEXT NOT NULL CHECK(lifecycle IN ('EXISTING','DEMOLISHED')), identity_revision INTEGER NOT NULL CHECK(typeof(identity_revision)='integer' AND identity_revision >= 0))",
            "CREATE TABLE building_staffing(building_id TEXT PRIMARY KEY NOT NULL REFERENCES economic_buildings(id), revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision >= 0))",
            "CREATE TABLE building_staffing_roles(building_id TEXT NOT NULL REFERENCES building_staffing(building_id), occupation_id TEXT NOT NULL REFERENCES occupation_catalog(id), employed INTEGER NOT NULL CHECK(typeof(employed)='integer' AND employed >= 0 AND employed <= 2147483647), pending_target INTEGER CHECK(pending_target IS NULL OR (typeof(pending_target)='integer' AND pending_target >= 0 AND pending_target <= 2147483647)), PRIMARY KEY(building_id,occupation_id))",
            "CREATE TABLE regional_labor_supply(region_id TEXT NOT NULL REFERENCES regions(id), occupation_id TEXT NOT NULL REFERENCES occupation_catalog(id), total_supply INTEGER NOT NULL CHECK(typeof(total_supply)='integer' AND total_supply >= 0), revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision >= 0), PRIMARY KEY(region_id,occupation_id))",
            "CREATE TABLE production_method_configurations(building_id TEXT PRIMARY KEY REFERENCES economic_buildings(id), effective_json TEXT NOT NULL, pending_json TEXT, configuration_revision INTEGER NOT NULL CHECK(configuration_revision >= 0), effective_revision INTEGER NOT NULL CHECK(effective_revision >= 0 AND effective_revision <= configuration_revision))",
            "CREATE TABLE production_batches(batch_id TEXT PRIMARY KEY, building_id TEXT NOT NULL REFERENCES economic_buildings(id), configuration_json TEXT NOT NULL, progress_units INTEGER NOT NULL CHECK(progress_units >= 0), status TEXT NOT NULL CHECK(status IN ('ACTIVE','PAUSED','SETTLING_COMPLETED','SETTLING_ABORTED','COMPLETED','ABORTED')), UNIQUE(batch_id, building_id))",
            "CREATE UNIQUE INDEX one_unfinished_batch_per_building ON production_batches(building_id) WHERE status IN ('ACTIVE','PAUSED','SETTLING_COMPLETED','SETTLING_ABORTED')",
            "CREATE TABLE production_executions(building_id TEXT PRIMARY KEY REFERENCES economic_buildings(id), current_batch_id TEXT, last_batch_id TEXT, boundary TEXT NOT NULL CHECK(boundary IN ('NONE','RECONFIGURING')), automatic INTEGER NOT NULL CHECK(automatic IN (0,1)), execution_revision INTEGER NOT NULL CHECK(execution_revision >= 0), last_completed_tick INTEGER NOT NULL CHECK(typeof(last_completed_tick)='integer' AND last_completed_tick>=0), processing_tick INTEGER CHECK(processing_tick IS NULL OR (typeof(processing_tick)='integer' AND processing_tick>last_completed_tick AND processing_tick-last_completed_tick=1)), pause_reasons_json TEXT NOT NULL, CHECK(current_batch_id IS NULL OR last_batch_id IS NULL OR current_batch_id <> last_batch_id), FOREIGN KEY(current_batch_id, building_id) REFERENCES production_batches(batch_id, building_id) DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(last_batch_id, building_id) REFERENCES production_batches(batch_id, building_id) DEFERRABLE INITIALLY DEFERRED)",
            "CREATE TABLE production_start_intents(batch_id TEXT PRIMARY KEY, building_id TEXT NOT NULL UNIQUE REFERENCES economic_buildings(id))",
            "CREATE TABLE inventory_locations(location_id TEXT PRIMARY KEY, state_json TEXT NOT NULL, revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision >= 0))");


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
                for (String ddl : EconomyFinancialSchema.DDL) statement.executeUpdate(ddl);
                for (String ddl : EconomyPaymentSchema.DDL) statement.executeUpdate(ddl);
                for (String ddl : EconomyLifecycleSchema.DDL) statement.executeUpdate(ddl);
                statement.executeUpdate("INSERT INTO economy_schema_migrations(version) VALUES (" + VERSION + ")");
                statement.executeUpdate("INSERT INTO economic_time VALUES(1,0,0,NULL,0)");
                statement.executeUpdate("INSERT INTO payroll_runtime VALUES(1,'BUILDING_STAFFING',0,0)");
                statement.executeUpdate("INSERT INTO payroll_payment_runtime VALUES(1,0,0)");
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
        SqliteTransactions.read(connection, snapshot -> {
            Set<String> expected = new HashSet<>();
            DDL.forEach(ddl -> expected.add(normalize(ddl)));
            EconomyFinancialSchema.DDL.forEach(ddl -> expected.add(normalize(ddl)));
            EconomyPaymentSchema.DDL.forEach(ddl -> expected.add(normalize(ddl)));
            EconomyLifecycleSchema.DDL.forEach(ddl -> expected.add(normalize(ddl)));
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
                EconomyPaymentValidation.validate(connection, snapshot);
                EconomyLifecycleValidation.validate(connection, snapshot);
                for (String table : List.of("companies", "regions")) {
                    try (var rows = statement.executeQuery("SELECT id FROM " + table)) {
                        while (rows.next()) storedId(rows.getString(1));
                    }
                }
                try (var rows = statement.executeQuery("SELECT * FROM economic_buildings")) {
                    while (rows.next()) SqliteBuildingRepository.decode(rows);
                }
                try (var rows = statement.executeQuery("SELECT b.id FROM economic_buildings b LEFT JOIN production_method_configurations m ON m.building_id=b.id LEFT JOIN production_executions e ON e.building_id=b.id LEFT JOIN building_staffing s ON s.building_id=b.id WHERE m.building_id IS NULL OR e.building_id IS NULL OR s.building_id IS NULL")) {
                    if (rows.next()) throw incompatible();
                }
                try(var rows=statement.executeQuery("SELECT b.id FROM economic_buildings b LEFT JOIN payroll_accruals p ON p.building_id=b.id AND p.closed_tick IS NULL WHERE b.lifecycle='EXISTING' AND p.id IS NULL")){
                    if(rows.next())throw incompatible();
                }
                long accepted=com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository.inSnapshot(connection,snapshot).acceptedTick();
                var executionReader=new com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionExecutionRepository(connection,new Object());
                try(var rows=statement.executeQuery("SELECT building_id FROM production_executions")){
                    while(rows.next()){
                        var e=executionReader.inSnapshot(snapshot,storedId(rows.getString(1)));
                        if(e.lastCompletedTick()>accepted||(e.processingTick()!=null&&e.processingTick()!=accepted))throw incompatible();
                        if(e.hasUnfinishedBatch()){
                            var actual=new TreeMap<String,Integer>();
                            try(var q=connection.prepareStatement("SELECT occupation_id,employed FROM building_staffing_roles WHERE building_id=? AND employed>0")){
                                q.setString(1,e.buildingId().toString());try(var staff=q.executeQuery()){while(staff.next())actual.put(staff.getString(1),Math.toIntExact(staff.getLong(2)));}
                            }
                            if(!actual.equals(e.batch().getConfiguration().workforcePlan().participatingWorkers()))throw incompatible();
                        }
                    }
                }
                // SUM remains derived. Integer overflow is an error, never a rounded REAL fallback.
                try (var rows = statement.executeQuery("SELECT b.region_id,r.occupation_id,SUM(r.employed) AS occupied,MAX(l.total_supply) AS total FROM building_staffing_roles r JOIN economic_buildings b ON b.id=r.building_id LEFT JOIN regional_labor_supply l ON l.region_id=b.region_id AND l.occupation_id=r.occupation_id GROUP BY b.region_id,r.occupation_id")) {
                    while (rows.next()) {
                        long occupied = rows.getLong("occupied");
                        long total = rows.getLong("total");
                        if (occupied < 0 || (rows.wasNull() ? occupied != 0 : occupied > total)) throw incompatible();
                    }
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
