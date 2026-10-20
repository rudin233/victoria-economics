package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionBatch;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionExecutionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class SchemaCompatibilityTest {
    @TempDir Path directory;
    private static final UUID BUILDING = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BATCH = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID EMPLOYEE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID RESERVED = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID SETTLED = UUID.fromString("00000000-0000-0000-0000-000000000005");

    // Literal pre-M1.2 v2 snapshots: fixtures do not depend on the new codec's writer.
    private static final String BATCH_JSON = """
            {"productionMethodSelections":{"base":"a"},
             "resolvedRecipe":{"inputs":{"ore":10.5},"outputs":{"metal":5.25},"requiredWorkers":{"worker":1}},
             "installedEquipment":100,"equipmentCapacity":100,
             "workforcePlan":{"employees":{"worker":["%s"]},"speedNumerator":1,"speedDenominator":1},
             "effectiveRevision":0}
            """.formatted(EMPLOYEE);
    private static final String INVENTORY_JSON = """
            {"location":"%s","capacity":100.0,"quantities":{"ore":12.5,"metal":1.25},
             "reservations":{"%s":{"ore":5.5}},
             "settlements":{"%s":{"batchId":"%s","consumed":{"ore":2.5},"produced":{"metal":1.125}}}}
            """.formatted(BUILDING, RESERVED, SETTLED, SETTLED);

    @Test void validHistoricalV2LoadsWithItsExistingDoubleUuidEquipmentAndProgressSemantics() throws Exception {
        Path file = fixture("v2.sqlite");
        seedBatch(file, BATCH_JSON, "ACTIVE", 600);
        byte[] before = Files.readAllBytes(file);
        try (var connection = SqliteEconomyDatabase.open(file)) {
            var batch = new SqliteProductionExecutionRepository(connection, new Object()).load(BUILDING).batch();
            assertEquals(1200, ProductionBatch.FULL_SPEED_TICKS);
            assertEquals(600, batch.getProgressUnits());
            assertEquals(0.5, batch.getProgress());
            assertEquals(100, batch.getConfiguration().installedEquipment());
            assertEquals(List.of(EMPLOYEE), batch.getConfiguration().workforcePlan().employees().get("worker"));
            assertEquals(10.5, batch.getConfiguration().resolvedRecipe().inputs().get("ore"));
            var stock = new SqliteInventoryStore(connection, new Object()).inspect(BUILDING);
            assertEquals(12.5, stock.quantities().get("ore"));
            assertEquals(5.5, stock.reservations().get(RESERVED).get("ore"));
            assertEquals(1.125, stock.settlements().get(SETTLED).produced().get("metal"));
            assertEquals(2, scalar(connection, "SELECT version FROM economy_schema_migrations"));
        }
        assertArrayEquals(before, Files.readAllBytes(file), "Successful read-only startup must not rewrite valid v2 data either");
    }

    @ParameterizedTest @ValueSource(strings = {"ACTIVE", "PAUSED", "SETTLING_COMPLETED", "SETTLING_ABORTED"})
    void everyUnfinishedLegacyBatchKeepsIts1200TickBasisAndIsRejectedByAnIncompatibleSchema(String status) throws Exception {
        Path file = fixture("batch.sqlite");
        long progress = status.equals("SETTLING_COMPLETED") ? 1200 : 1199;
        seedBatch(file, BATCH_JSON, status, progress);
        try (var connection = SqliteEconomyDatabase.open(file)) {
            var batch = new SqliteProductionExecutionRepository(connection, new Object()).load(BUILDING).batch();
            assertEquals(status, batch.getStatus().name());
            assertEquals(progress, batch.getProgressUnits());
            assertEquals(1200, batch.getMaximumProgressUnits());
            assertEquals(progress / 1200.0, batch.getProgress());
            sql(connection, "UPDATE economy_schema_migrations SET version=3");
        }
        assertRejectedUnchanged(file); // The v2 payload is not guessed/converted under a future version.
    }

    @Test void nonemptyV1IsPreservedAndNotUpgradedOrCleared() throws Exception {
        Path file = directory.resolve("v1.sqlite");
        try (var connection = connect(file)) {
            sql(connection, "CREATE TABLE economy_schema_migrations(version INTEGER PRIMARY KEY)");
            sql(connection, "INSERT INTO economy_schema_migrations VALUES(1)");
            sql(connection, "CREATE TABLE economic_buildings(id TEXT PRIMARY KEY,current_equipment INTEGER)");
            sql(connection, "INSERT INTO economic_buildings VALUES('legacy-building',73)");
            sql(connection, "CREATE TABLE building_production_execution(building_id TEXT,state_json TEXT)");
            sql(connection, "INSERT INTO building_production_execution VALUES('legacy-building','{\"progress\":600,\"ore\":12.5}')");
        }
        assertRejectedUnchanged(file);
        try (var connection = readOnly(file)) {
            assertEquals(1, scalar(connection, "SELECT version FROM economy_schema_migrations"));
            assertEquals(73, scalar(connection, "SELECT current_equipment FROM economic_buildings"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM building_production_execution"));
        }
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 3, 99})
    void unknownSchemaVersionsAreRejectedBeforeAnyWritableHandleIsExposed(int version) throws Exception {
        Path file = fixture("unknown.sqlite");
        try (var connection = connect(file)) { sql(connection, "UPDATE economy_schema_migrations SET version=" + version); }
        assertRejectedUnchanged(file);
    }

    @ParameterizedTest @ValueSource(strings = {"DELETE FROM economy_schema_migrations", "INSERT INTO economy_schema_migrations VALUES(3)",
            "DROP TABLE equipment_holdings", "DROP INDEX one_unfinished_batch_per_building",
            "ALTER TABLE inventory_locations ADD COLUMN future_version INTEGER", "CREATE TABLE future_layout(id TEXT)",
            "CREATE TABLE sqlitex_future_layout(id TEXT)"})
    void incompleteOrMixedSchemasAreRejectedWithoutRepair(String mutation) throws Exception {
        Path file = fixture("incomplete.sqlite");
        try (var connection = connect(file)) { sql(connection, mutation); }
        assertRejectedUnchanged(file);
    }

    @ParameterizedTest @ValueSource(strings = {"constraint", "type", "index"})
    void matchingColumnNamesDoNotHideIncompatibleConstraintsOrTypes(String kind) throws Exception {
        UnaryOperator<String> change = switch (kind) {
            case "constraint" -> ddl -> ddl.replace("CHECK(progress_units >= 0)", "CHECK(progress_units >= -1)");
            case "type" -> ddl -> ddl.replace("state_json TEXT NOT NULL", "state_json INTEGER NOT NULL");
            default -> ddl -> ddl.replace("CREATE UNIQUE INDEX one_unfinished_batch_per_building", "CREATE INDEX one_unfinished_batch_per_building");
        };
        assertRejectedUnchanged(fixture("altered.sqlite", change));
    }

    @Test void foreignKeyInconsistencyIsRejectedWithoutDeletingOrphans() throws Exception {
        Path file = fixture("orphan.sqlite");
        try (var connection = connect(file)) {
            sql(connection, "INSERT INTO production_start_intents VALUES('" + BATCH + "','" + UUID.randomUUID() + "')");
        }
        assertRejectedUnchanged(file);
    }

    @ParameterizedTest @ValueSource(strings = {"missingEquipment", "missingRevision", "futureVersion", "futureWorkers", "fractionalInteger",
            "stringNumber", "duplicate", "null", "trailing", "comment", "invalidUuid", "invalidSpeed", "invalidRecipe"})
    void unrecognizedBatchSnapshotsAreRejectedAtStartupAndRepositoryLoad(String kind) throws Exception {
        String malformed = switch (kind) {
            case "missingEquipment" -> BATCH_JSON.replace("\"installedEquipment\":100,", "");
            case "missingRevision" -> BATCH_JSON.replace(",\n \"effectiveRevision\":0", "");
            case "futureVersion" -> BATCH_JSON.replace("\"effectiveRevision\":0", "\"effectiveRevision\":0,\"formatVersion\":3,\"fullSpeedTicks\":200");
            case "futureWorkers" -> BATCH_JSON.replace("\"employees\"", "\"participatingWorkers\"");
            case "fractionalInteger" -> BATCH_JSON.replace("\"installedEquipment\":100", "\"installedEquipment\":99.5");
            case "stringNumber" -> BATCH_JSON.replace("\"speedNumerator\":1", "\"speedNumerator\":\"1\"");
            case "duplicate" -> BATCH_JSON.replace("\"effectiveRevision\":0", "\"effectiveRevision\":0,\"effectiveRevision\":9");
            case "null" -> "null";
            case "trailing" -> BATCH_JSON + " {}";
            case "comment" -> "/* future */" + BATCH_JSON;
            case "invalidUuid" -> BATCH_JSON.replace(EMPLOYEE.toString(), "npc-uuid-from-another-format");
            case "invalidSpeed" -> BATCH_JSON.replace("\"speedNumerator\":1", "\"speedNumerator\":0");
            default -> BATCH_JSON.replace("\"metal\":5.25", "\"metal\":-5.25");
        };
        assertNotEquals(BATCH_JSON, malformed, "Fixture mutation must actually change the snapshot");
        Path file = fixture("batch-json.sqlite");
        seedBatch(file, malformed, "ACTIVE", 600);
        assertRejectedUnchanged(file);
        byte[] before = Files.readAllBytes(file);
        try (var connection = connect(file)) {
            assertThrows(IllegalStateException.class, () -> new SqliteProductionExecutionRepository(connection, new Object()).load(BUILDING));
        }
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @ParameterizedTest @ValueSource(strings = {"missingCapacity", "nullCapacity", "stringQuantity", "duplicateQuantity", "futureReceipt",
            "wrongLocation", "wrongReceipt", "negativeReceipt", "infiniteQuantity", "null", "trailing"})
    void unrecognizedInventorySnapshotsCannotReachBusinessMutation(String kind) throws Exception {
        String malformed = switch (kind) {
            case "missingCapacity" -> INVENTORY_JSON.replace("\"capacity\":100.0,", "");
            case "nullCapacity" -> INVENTORY_JSON.replace("\"capacity\":100.0", "\"capacity\":null");
            case "stringQuantity" -> INVENTORY_JSON.replace("\"ore\":12.5", "\"ore\":\"12.5\"");
            case "duplicateQuantity" -> INVENTORY_JSON.replace("\"ore\":12.5", "\"ore\":12.5,\"ore\":0");
            case "futureReceipt" -> INVENTORY_JSON.replace("\"consumed\"", "\"outcome\":\"ABORTED\",\"consumed\"");
            case "wrongLocation" -> INVENTORY_JSON.replace(BUILDING.toString(), UUID.randomUUID().toString());
            case "wrongReceipt" -> INVENTORY_JSON.replace("\"batchId\":\"" + SETTLED, "\"batchId\":\"" + BATCH);
            case "negativeReceipt" -> INVENTORY_JSON.replace("\"ore\":2.5", "\"ore\":-2.5");
            case "infiniteQuantity" -> INVENTORY_JSON.replace("\"ore\":12.5", "\"ore\":1e999");
            case "null" -> "null";
            default -> INVENTORY_JSON + " {}";
        };
        assertNotEquals(INVENTORY_JSON, malformed);
        Path file = fixture("inventory-json.sqlite");
        try (var connection = connect(file)) { updateInventory(connection, malformed); }
        assertRejectedUnchanged(file);
        byte[] before = Files.readAllBytes(file);
        try (var connection = connect(file)) {
            var store = new SqliteInventoryStore(connection, new Object());
            assertThrows(IllegalStateException.class, () -> store.inspect(BUILDING));
            assertThrows(IllegalStateException.class, () -> store.deposit(BUILDING, Map.of("ore", 1.0)));
        }
        assertArrayEquals(before, Files.readAllBytes(file), "Malformed JSON must be rejected before UPDATE");
    }

    @Test void invalidUnreferencedHistoricalBatchIsStillCheckedBeforeRecoveryWrites() throws Exception {
        Path file = fixture("history.sqlite");
        seedBatch(file, "{}", "COMPLETED", 1200);
        try (var connection = connect(file)) { sql(connection, "UPDATE production_executions SET current_batch_id=NULL"); }
        assertRejectedUnchanged(file);
    }

    @Test void integerDatabaseProgressCannotBeSilentlyTruncatedFromARealValue() throws Exception {
        Path file = fixture("fractional-progress.sqlite");
        seedBatch(file, BATCH_JSON, "ACTIVE", 600);
        try (var connection = connect(file)) { sql(connection, "UPDATE production_batches SET progress_units=600.5"); }
        assertRejectedUnchanged(file);
    }

    @Test void snapshotValidationAlsoWorksOnAPhysicallyReadOnlyConnection() throws Exception {
        Path file = fixture("readonly.sqlite");
        seedBatch(file, BATCH_JSON, "ACTIVE", 600);
        byte[] before = Files.readAllBytes(file);
        try (var connection = readOnly(file)) {
            EconomySchema.validateExisting(connection);
            assertThrows(SQLException.class, () -> sql(connection, "UPDATE economy_schema_migrations SET version=3"));
        }
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test void readOnlyGateObservesAndPreservesIncompatibleDataInAnExistingWal() throws Exception {
        Path file = fixture("wal.sqlite");
        try (var writer = connect(file)) {
            sql(writer, "PRAGMA journal_mode=WAL");
            sql(writer, "UPDATE economy_schema_migrations SET version=99");
            Path wal = Path.of(file + "-wal");
            assertTrue(Files.exists(wal));
            byte[] beforeWal = Files.readAllBytes(wal);
            assertRejectedUnchanged(file);
            assertArrayEquals(beforeWal, Files.readAllBytes(wal), "Read-only preflight must not checkpoint or rewrite the WAL");
        }
    }

    @Test void emptyExistingFileIsNotAutomaticallyRebuilt() throws Exception {
        Path file = directory.resolve("empty.sqlite");
        Files.createFile(file);
        assertRejectedUnchanged(file);
        assertEquals(0, Files.size(file));
    }

    @Test void writableRecheckOfAnExistingFileAlsoForbidsCreationIfItBecameEmpty() throws Exception {
        Path file = directory.resolve("became-empty.sqlite");
        try (var connection = connect(file)) {
            byte[] before = Files.readAllBytes(file);
            assertThrows(IllegalStateException.class, () -> EconomySchema.initialize(connection, false));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM sqlite_master"));
            assertArrayEquals(before, Files.readAllBytes(file));
        }
    }

    @Test void nonDatabaseFileIsPreserved() throws Exception {
        Path file = directory.resolve("not-a-database.sqlite");
        Files.writeString(file, "existing user data", StandardCharsets.UTF_8);
        assertRejectedUnchanged(file);
    }

    @Test void absentDatabaseCreatesOnlyTheCurrentV2SchemaAndOriginalJsonShapes() throws Exception {
        Path file = directory.resolve("new.sqlite");
        assertFalse(Files.exists(file));
        try (var connection = SqliteEconomyDatabase.open(file)) {
            assertEquals(2, scalar(connection, "SELECT version FROM economy_schema_migrations"));
            var store = new SqliteInventoryStore(connection, new Object());
            store.createLocation(BUILDING, 100);
            store.deposit(BUILDING, Map.of("ore", 12.5));
            assertEquals(12.5, store.inspect(BUILDING).quantities().get("ore"));
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT state_json FROM inventory_locations")) {
                assertTrue(rows.next());
                assertEquals(Set.of("location", "capacity", "quantities", "reservations", "settlements"),
                        keys(connection, rows.getString(1)));
            }
            assertEquals(Set.of("productionMethodSelections", "resolvedRecipe", "installedEquipment", "equipmentCapacity", "workforcePlan", "effectiveRevision"),
                    keys(connection, EconomyStateCodec.encodeBatchConfiguration(EconomyStateCodec.decodeBatchConfiguration(BATCH_JSON))));
        }
    }

    @Test void validFractionsSurviveCurrentV2MutationsWithoutRoundingOrFormatTagging() throws Exception {
        Path file = fixture("fractions.sqlite");
        try (var connection = SqliteEconomyDatabase.open(file)) {
            var store = new SqliteInventoryStore(connection, new Object());
            store.deposit(BUILDING, Map.of("wood", 0.125));
            var stock = store.inspect(BUILDING);
            assertEquals(12.5, stock.quantities().get("ore"));
            assertEquals(0.125, stock.quantities().get("wood"));
            assertEquals(1.125, stock.settlements().get(SETTLED).produced().get("metal"));
        }
        try (var connection = connect(file)) { sql(connection, "UPDATE economy_schema_migrations SET version=3"); }
        assertRejectedUnchanged(file);
    }

    @Test void currentV2DoubleSignedZeroIsPreservedAcrossReadAndWrite() throws Exception {
        Path file = fixture("signed-zero.sqlite");
        try (var connection = connect(file)) {
            updateInventory(connection, INVENTORY_JSON.replace("\"metal\":1.25", "\"metal\":-0.0"));
        }
        try (var connection = SqliteEconomyDatabase.open(file)) {
            var store = new SqliteInventoryStore(connection, new Object());
            assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(store.inspect(BUILDING).quantities().get("metal")));
            store.deposit(BUILDING, Map.of("wood", 0.125));
            assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(store.inspect(BUILDING).quantities().get("metal")));
        }
    }

    @Test void compatibilityValidationCannotResolveOrReadAnUncommittedLocalTransaction() throws Exception {
        Path file = fixture("unresolved.sqlite");
        try (var connection = connect(file)) {
            connection.setAutoCommit(false);
            try {
                sql(connection, "UPDATE economy_schema_migrations SET version=99");
                assertThrows(CommitOutcomeUnknownException.class, () -> EconomySchema.validateExisting(connection));
                assertThrows(CommitOutcomeUnknownException.class, () -> EconomySchema.initialize(connection));
                assertFalse(connection.getAutoCommit());
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            EconomySchema.validateExisting(connection);
        }
    }

    private Path fixture(String name) throws Exception { return fixture(name, UnaryOperator.identity()); }

    private Path fixture(String name, UnaryOperator<String> change) throws Exception {
        Path file = directory.resolve(name);
        String ddl;
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/sqlite/schema-v2.sql"))) {
            ddl = change.apply(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (var connection = connect(file)) {
            for (String command : ddl.split(";")) if (!command.isBlank()) sql(connection, command);
            sql(connection, "INSERT INTO economic_buildings VALUES('" + BUILDING + "','factory','ACTIVE')");
            sql(connection, "INSERT INTO production_method_configurations VALUES('" + BUILDING + "','{\"methods\":{\"base\":\"a\"}}',NULL,0,0)");
            sql(connection, "INSERT INTO production_executions VALUES('" + BUILDING + "',NULL,NULL,'NONE',0,0,0)");
            try (var insert = connection.prepareStatement("INSERT INTO inventory_locations VALUES(?,?)")) {
                insert.setString(1, BUILDING.toString());
                insert.setString(2, INVENTORY_JSON);
                insert.executeUpdate();
            }
        }
        return file;
    }

    private void seedBatch(Path file, String json, String status, long progress) throws SQLException {
        try (var connection = connect(file); var insert = connection.prepareStatement("INSERT INTO production_batches VALUES(?,?,?,?,?)")) {
            insert.setString(1, BATCH.toString());
            insert.setString(2, BUILDING.toString());
            insert.setString(3, json);
            insert.setLong(4, progress);
            insert.setString(5, status);
            insert.executeUpdate();
            sql(connection, "UPDATE production_executions SET current_batch_id='" + BATCH + "'");
        }
    }

    private void assertRejectedUnchanged(Path file) throws Exception {
        byte[] before = Files.readAllBytes(file);
        var failure = assertThrows(IllegalStateException.class, () -> {
            try (var ignored = SqliteEconomyDatabase.open(file)) { fail("Incompatible file was opened for business"); }
        });
        assertTrue(failure.getMessage().contains("No migration was attempted"));
        assertTrue(Files.exists(file));
        assertArrayEquals(before, Files.readAllBytes(file), "Rejection must preserve every database byte");
    }

    private static Connection connect(Path file) throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + file); }
    private static Connection readOnly(Path file) throws SQLException {
        var config = new SQLiteConfig();
        config.setReadOnly(true);
        return DriverManager.getConnection("jdbc:sqlite:" + file, config.toProperties());
    }
    private static void sql(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static long scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
    private static void updateInventory(Connection connection, String json) throws SQLException {
        try (var update = connection.prepareStatement("UPDATE inventory_locations SET state_json=?")) {
            update.setString(1, json);
            update.executeUpdate();
        }
    }

    private static Set<String> keys(Connection connection, String json) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT key FROM json_each(?)")) {
            statement.setString(1, json);
            try (var rows = statement.executeQuery()) {
                Set<String> result = new HashSet<>();
                while (rows.next()) result.add(rows.getString(1));
                return result;
            }
        }
    }
}
