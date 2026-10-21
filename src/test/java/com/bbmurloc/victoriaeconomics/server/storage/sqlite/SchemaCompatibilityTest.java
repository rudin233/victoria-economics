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

    // Literal v8 and historical v2-v7 fixtures, independent of the current writer.
    private static final String BATCH_JSON = """
            {"formatVersion":8,"fullSpeedTicks":200,"snapshot":{"productionMethodSelections":{"base":"a"},
             "resolvedRecipe":{"inputs":{"ore":10},"outputs":{"metal":5},"requiredWorkers":{"worker":1}},
             "workforcePlan":{"participatingWorkers":{"worker":1},"speedNumerator":1,"speedDenominator":1},
             "effectiveRevision":0}}
            """.formatted(EMPLOYEE);
    private static final String V3_EQUIPMENT_JSON = """
            {"buildingId":"%s","type":"factory_machine","capacity":100,"installed":100,"uninstalled":0,
             "protectedByBatch":"%s","requests":[]}
            """.formatted(BUILDING, BATCH);
    private static final String INVENTORY_JSON = """
            {"formatVersion":8,"snapshot":{"location":"%s","capacity":100,"quantities":{"ore":12,"metal":1},
             "reservations":{"%s":{"ore":5}},
             "settlements":{"%s":{"command":{"batchId":"%s","outcome":"COMPLETED","inputs":{"ore":2},"outputs":{"metal":1}}}}}}
            """.formatted(BUILDING, RESERVED, SETTLED, SETTLED);
    private static final String LEGACY_BATCH_JSON = """
            {"productionMethodSelections":{"base":"a"},
             "resolvedRecipe":{"inputs":{"ore":10.5},"outputs":{"metal":5.25},"requiredWorkers":{"worker":1}},
             "installedEquipment":100,"equipmentCapacity":100,
             "workforcePlan":{"employees":{"worker":["%s"]},"speedNumerator":1,"speedDenominator":1},
             "effectiveRevision":0}
            """.formatted(EMPLOYEE);
    private static final String V3_BATCH_JSON = """
            {"formatVersion":3,"fullSpeedTicks":200,"snapshot":{"productionMethodSelections":{"base":"a"},
             "resolvedRecipe":{"inputs":{"ore":10},"outputs":{"metal":5},"requiredWorkers":{"worker":1}},
             "installedEquipment":100,"equipmentCapacity":100,
             "workforcePlan":{"employees":{"worker":["%s"]},"speedNumerator":1,"speedDenominator":1},
             "effectiveRevision":0}}
            """.formatted(EMPLOYEE);
    private static final String LEGACY_INVENTORY_JSON = """
            {"location":"%s","capacity":100.0,"quantities":{"ore":12.5,"metal":1.25},
             "reservations":{"%s":{"ore":5.5}},
             "settlements":{"%s":{"batchId":"%s","consumed":{"ore":2.5},"produced":{"metal":1.125}}}}
            """.formatted(BUILDING, RESERVED, SETTLED, SETTLED);

    private static final String V6_BATCH_JSON="""
            {"formatVersion":6,"fullSpeedTicks":200,"snapshot":{"productionMethodSelections":{"base":"a"},
             "resolvedRecipe":{"inputs":{"ore":10},"outputs":{"metal":5},"requiredWorkers":{"worker":1}},
             "workforcePlan":{"employees":{"worker":["%s"]},"speedNumerator":1,"speedDenominator":1},"effectiveRevision":0}}
            """.formatted(EMPLOYEE);

    @Test void validV8LoadsIntegerGoodsAndIntegerParticipation() throws Exception {
        Path file = fixture("v8.sqlite");
        seedBatch(file, BATCH_JSON, "ACTIVE", 100);
        byte[] before = Files.readAllBytes(file);
        try (var connection = SqliteEconomyDatabase.open(file)) {
            var batch = new SqliteProductionExecutionRepository(connection, new Object()).load(BUILDING).batch();
            assertEquals(200, ProductionBatch.FULL_SPEED_TICKS);
            assertEquals(100, batch.getProgressUnits());
            assertEquals(0.5, batch.getProgress());

            assertEquals(1, batch.getConfiguration().workforcePlan().participatingWorkers().get("worker"));
            assertEquals(10L, batch.getConfiguration().resolvedRecipe().inputs().get("ore"));
            var stock = new SqliteInventoryStore(connection, new Object()).inspect(BUILDING);
            assertEquals(12L, stock.quantities().get("ore"));
            assertEquals(5L, stock.reservations().get(RESERVED).get("ore"));
            assertEquals(1L, stock.settlements().get(SETTLED).produced().get("metal"));
            assertEquals(8, scalar(connection, "SELECT version FROM economy_schema_migrations"));
        }
        assertArrayEquals(before, Files.readAllBytes(file), "Read-only startup must not rewrite valid v8 data");
    }

    @ParameterizedTest @ValueSource(strings = {"ACTIVE", "PAUSED", "SETTLING_COMPLETED", "SETTLING_ABORTED"})
    void everyUnfinishedLegacyV2BatchIsRejectedWithoutReinterpretingIts1200TickBasis(String status) throws Exception {
        Path file = legacyFixture("batch.sqlite");
        long progress = status.equals("SETTLING_COMPLETED") ? 1200 : 1199;
        seedBatch(file, LEGACY_BATCH_JSON, status, progress);
        assertRejectedUnchanged(file);
        try (var connection = readOnly(file)) {
            assertEquals(progress, scalar(connection, "SELECT progress_units FROM production_batches"));
            assertEquals(2, scalar(connection, "SELECT version FROM economy_schema_migrations"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ACTIVE", "PAUSED", "SETTLING_COMPLETED", "SETTLING_ABORTED"})
    void currentV8BatchStatesUseThe200TickBasis(String status) throws Exception {
        Path file = fixture("current-batch.sqlite");
        long progress = status.equals("SETTLING_COMPLETED") ? 200 : 199;
        seedBatch(file, BATCH_JSON, status, progress);
        try (var connection = SqliteEconomyDatabase.open(file)) {
            var batch = new SqliteProductionExecutionRepository(connection, new Object()).load(BUILDING).batch();
            assertEquals(status, batch.getStatus().name());
            assertEquals(progress, batch.getProgressUnits());
            assertEquals(200, batch.getMaximumProgressUnits());
        }
    }

    @Test void nonemptyV1IsPreservedAndNotUpgradedOrCleared() throws Exception {
        Path file = directory.resolve("v1.sqlite");
        try (var connection = connect(file)) {
            sql(connection, "CREATE TABLE economy_schema_migrations(version INTEGER PRIMARY KEY)");
            sql(connection, "INSERT INTO economy_schema_migrations VALUES(1)");
            sql(connection, "CREATE TABLE economic_buildings(id TEXT PRIMARY KEY,current_equipment INTEGER)");
            sql(connection, "INSERT INTO economic_buildings VALUES('legacy-building',73)");
            sql(connection, "CREATE TABLE building_production_execution(building_id TEXT,state_json TEXT)");
            sql(connection, "INSERT INTO building_production_execution VALUES('legacy-building','{\"progress\":600,\"ore\":12}')");
        }
        assertRejectedUnchanged(file);
        try (var connection = readOnly(file)) {
            assertEquals(1, scalar(connection, "SELECT version FROM economy_schema_migrations"));
            assertEquals(73, scalar(connection, "SELECT current_equipment FROM economic_buildings"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM building_production_execution"));
        }
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 2, 3, 4, 5, 6, 7, 99})
    void unknownSchemaVersionsAreRejectedBeforeAnyWritableHandleIsExposed(int version) throws Exception {
        Path file = fixture("unknown.sqlite");
        try (var connection = connect(file)) { sql(connection, "UPDATE economy_schema_migrations SET version=" + version); }
        assertRejectedUnchanged(file);
    }

    @ParameterizedTest @ValueSource(strings = {"DELETE FROM economy_schema_migrations", "INSERT INTO economy_schema_migrations VALUES(2)",
            "DROP TABLE payroll_employment_history", "DROP INDEX one_unfinished_batch_per_building",
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

    @ParameterizedTest @ValueSource(strings = {"missingWorkforce", "missingRevision", "futureVersion", "futureWorkers", "fractionalInteger",
            "legacyBasis", "legacySnapshot", "v3Snapshot", "removedEquipment", "wrongVersion", "fractionalGoods", "floatingInteger", "exponent", "overflow", "noWorkers",
            "stringNumber", "duplicate", "null", "trailing", "comment", "invalidCount", "invalidSpeed", "invalidRecipe"})
    void unrecognizedBatchSnapshotsAreRejectedAtStartupAndRepositoryLoad(String kind) throws Exception {
        String malformed = switch (kind) {
            case "missingWorkforce" -> BATCH_JSON.replace("\"workforcePlan\"", "\"oldWorkers\"");
            case "missingRevision" -> BATCH_JSON.replace(",\n \"effectiveRevision\":0", "");
            case "futureVersion" -> BATCH_JSON.replace("\"effectiveRevision\":0", "\"effectiveRevision\":0,\"formatVersion\":8,\"fullSpeedTicks\":200");
            case "futureWorkers" -> BATCH_JSON.replace("\"participatingWorkers\"", "\"employees\"");
            case "fractionalInteger" -> BATCH_JSON.replace("\"speedDenominator\":1", "\"speedDenominator\":1.5");
            case "legacyBasis" -> BATCH_JSON.replace("\"fullSpeedTicks\":200", "\"fullSpeedTicks\":1200");
            case "legacySnapshot" -> LEGACY_BATCH_JSON;
            case "v3Snapshot" -> V3_BATCH_JSON;
            case "removedEquipment" -> V3_BATCH_JSON.replace("\"formatVersion\":3", "\"formatVersion\":8");
            case "wrongVersion" -> BATCH_JSON.replace("\"formatVersion\":8", "\"formatVersion\":2");
            case "fractionalGoods" -> BATCH_JSON.replace("\"ore\":10", "\"ore\":10.5");
            case "floatingInteger" -> BATCH_JSON.replace("\"ore\":10", "\"ore\":10.0");
            case "exponent" -> BATCH_JSON.replace("\"ore\":10", "\"ore\":1e1");
            case "overflow" -> BATCH_JSON.replace("\"ore\":10", "\"ore\":9223372036854775808");
            case "noWorkers" -> BATCH_JSON.replace("\"requiredWorkers\":{\"worker\":1}", "\"requiredWorkers\":{}");
            case "stringNumber" -> BATCH_JSON.replace("\"speedNumerator\":1", "\"speedNumerator\":\"1\"");
            case "duplicate" -> BATCH_JSON.replace("\"effectiveRevision\":0", "\"effectiveRevision\":0,\"effectiveRevision\":9");
            case "null" -> "null";
            case "trailing" -> BATCH_JSON + " {}";
            case "comment" -> "/* future */" + BATCH_JSON;
            case "invalidCount" -> BATCH_JSON.replace("\"participatingWorkers\":{\"worker\":1}", "\"participatingWorkers\":{\"worker\":0}");
            case "invalidSpeed" -> BATCH_JSON.replace("\"speedNumerator\":1", "\"speedNumerator\":0");
            default -> BATCH_JSON.replace("\"metal\":5", "\"metal\":-5");
        };
        assertNotEquals(BATCH_JSON, malformed, "Fixture mutation must actually change the snapshot");
        Path file = fixture("batch-json.sqlite");
        seedBatch(file, malformed, "ACTIVE", 100);
        assertRejectedUnchanged(file);
        byte[] before = Files.readAllBytes(file);
        try (var connection = connect(file)) {
            assertThrows(IllegalStateException.class, () -> new SqliteProductionExecutionRepository(connection, new Object()).load(BUILDING));
        }
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @ParameterizedTest @ValueSource(strings = {"missingCapacity", "nullCapacity", "stringQuantity", "duplicateQuantity", "futureReceipt",
            "legacySnapshot", "v3Snapshot", "wrongVersion", "fractionalGoods", "floatingInteger", "exponent", "overflow", "wrongOutcome", "zeroReservation",
            "wrongLocation", "wrongReceipt", "negativeReceipt", "infiniteQuantity", "null", "trailing"})
    void unrecognizedInventorySnapshotsCannotReachBusinessMutation(String kind) throws Exception {
        String malformed = switch (kind) {
            case "missingCapacity" -> INVENTORY_JSON.replace("\"capacity\":100,", "");
            case "nullCapacity" -> INVENTORY_JSON.replace("\"capacity\":100", "\"capacity\":null");
            case "legacySnapshot" -> LEGACY_INVENTORY_JSON;
            case "v3Snapshot" -> INVENTORY_JSON.replace("\"formatVersion\":8", "\"formatVersion\":3");
            case "wrongVersion" -> INVENTORY_JSON.replace("\"formatVersion\":8", "\"formatVersion\":2");
            case "fractionalGoods" -> INVENTORY_JSON.replace("\"ore\":12", "\"ore\":12.5");
            case "floatingInteger" -> INVENTORY_JSON.replace("\"ore\":12", "\"ore\":12.0");
            case "exponent" -> INVENTORY_JSON.replace("\"ore\":12", "\"ore\":12e0");
            case "overflow" -> INVENTORY_JSON.replace("\"ore\":12", "\"ore\":9223372036854775808");
            case "wrongOutcome" -> INVENTORY_JSON.replace("COMPLETED", "PARTIAL");
            case "zeroReservation" -> INVENTORY_JSON.replace("\"ore\":5", "\"ore\":0");
            case "stringQuantity" -> INVENTORY_JSON.replace("\"ore\":12", "\"ore\":\"12\"");
            case "duplicateQuantity" -> INVENTORY_JSON.replace("\"ore\":12", "\"ore\":12,\"ore\":0");
            case "futureReceipt" -> INVENTORY_JSON.replace("\"inputs\"", "\"outcome\":\"ABORTED\",\"inputs\"");
            case "wrongLocation" -> INVENTORY_JSON.replace(BUILDING.toString(), UUID.randomUUID().toString());
            case "wrongReceipt" -> INVENTORY_JSON.replace("\"batchId\":\"" + SETTLED, "\"batchId\":\"" + BATCH);
            case "negativeReceipt" -> INVENTORY_JSON.replace("\"ore\":2", "\"ore\":-2");
            case "infiniteQuantity" -> INVENTORY_JSON.replace("\"ore\":12", "\"ore\":1e999");
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
            assertThrows(IllegalStateException.class, () -> store.deposit(BUILDING, Map.of("ore", 1L)));
        }
        assertArrayEquals(before, Files.readAllBytes(file), "Malformed JSON must be rejected before UPDATE");
    }

    @Test void invalidUnreferencedHistoricalBatchIsStillCheckedBeforeRecoveryWrites() throws Exception {
        Path file = fixture("history.sqlite");
        seedBatch(file, "{}", "COMPLETED", 200);
        try (var connection = connect(file)) { sql(connection, "UPDATE production_executions SET current_batch_id=NULL"); }
        assertRejectedUnchanged(file);
    }

    @Test void integerDatabaseProgressCannotBeSilentlyTruncatedFromARealValue() throws Exception {
        Path file = fixture("fractional-progress.sqlite");
        seedBatch(file, BATCH_JSON, "ACTIVE", 100);
        try (var connection = connect(file)) { sql(connection, "UPDATE production_batches SET progress_units=100.5"); }
        assertRejectedUnchanged(file);
    }

    @Test void snapshotValidationAlsoWorksOnAPhysicallyReadOnlyConnection() throws Exception {
        Path file = fixture("readonly.sqlite");
        seedBatch(file, BATCH_JSON, "ACTIVE", 100);
        byte[] before = Files.readAllBytes(file);
        try (var connection = readOnly(file)) {
            EconomySchema.validateExisting(connection);
            assertThrows(SQLException.class, () -> sql(connection, "UPDATE economy_schema_migrations SET version=2"));
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

    @Test void oldV2MainFileAndWalRemainByteForByteUnchangedOnRejection() throws Exception {
        Path file = legacyFixture("v2-wal.sqlite");
        seedBatch(file, LEGACY_BATCH_JSON, "SETTLING_ABORTED", 1199);
        try (var writer = connect(file)) {
            sql(writer, "PRAGMA journal_mode=WAL");
            sql(writer, "PRAGMA wal_autocheckpoint=0");
            sql(writer, "UPDATE production_batches SET progress_units=1198");
            Path wal = Path.of(file + "-wal");
            assertTrue(Files.size(wal) > 0);
            byte[] beforeWal = Files.readAllBytes(wal);
            assertRejectedUnchanged(file);
            assertArrayEquals(beforeWal, Files.readAllBytes(wal));
            try (var reader = readOnly(file)) {
                assertEquals(2, scalar(reader, "SELECT version FROM economy_schema_migrations"));
                assertEquals(1198, scalar(reader, "SELECT progress_units FROM production_batches"));
            }
            assertArrayEquals(beforeWal, Files.readAllBytes(wal));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ACTIVE", "PAUSED", "SETTLING_COMPLETED", "SETTLING_ABORTED", "COMPLETED", "ABORTED"})
    void oldV3BatchesAndDedicatedAssetsAreRejectedWithoutChangingTheFile(String status) throws Exception {
        Path file = fixture("v3.sqlite", UnaryOperator.identity(), 3);
        long progress = status.endsWith("COMPLETED") ? 200 : 199;
        seedBatch(file, V3_BATCH_JSON, status, progress);
        try (var connection = connect(file)) {
            sql(connection, "INSERT INTO equipment_holdings VALUES('" + BUILDING + "','" + V3_EQUIPMENT_JSON + "')");
            if (status.equals("COMPLETED") || status.equals("ABORTED"))
                sql(connection, "UPDATE production_executions SET boundary='METHODS'");
        }
        assertRejectedUnchanged(file);
        try (var reader = readOnly(file); var query = reader.createStatement();
             var rows = query.executeQuery("SELECT configuration_json, progress_units, status FROM production_batches")) {
            assertTrue(rows.next());
            assertEquals(V3_BATCH_JSON, rows.getString(1));
            assertEquals(progress, rows.getLong(2));
            assertEquals(status, rows.getString(3));
            assertEquals(3, scalar(reader, "SELECT version FROM economy_schema_migrations"));
            assertEquals(1, scalar(reader, "SELECT COUNT(*) FROM equipment_holdings"));
            assertEquals(100, scalar(reader, "SELECT json_extract(state_json,'$.installed') FROM equipment_holdings"));
        }
    }

    @Test void oldV3MainAndWalRemainUnchangedAndNoAssetsAreConvertedToGoods() throws Exception {
        Path file = fixture("v3-wal.sqlite", UnaryOperator.identity(), 3);
        seedBatch(file, V3_BATCH_JSON, "ACTIVE", 100);
        try (var writer = connect(file)) {
            sql(writer, "PRAGMA journal_mode=WAL");
            sql(writer, "PRAGMA wal_autocheckpoint=0");
            sql(writer, "INSERT INTO equipment_holdings VALUES('" + BUILDING + "','" + V3_EQUIPMENT_JSON + "')");
            Path wal = Path.of(file + "-wal");
            assertTrue(Files.size(wal) > 0);
            byte[] beforeWal = Files.readAllBytes(wal);
            assertRejectedUnchanged(file);
            assertArrayEquals(beforeWal, Files.readAllBytes(wal));
            try (var reader = readOnly(file)) {
                assertEquals(3, scalar(reader, "SELECT version FROM economy_schema_migrations"));
                assertEquals(1, scalar(reader, "SELECT COUNT(*) FROM equipment_holdings"));
                assertEquals(12, scalar(reader, "SELECT json_extract(state_json,'$.snapshot.quantities.ore') FROM inventory_locations"));
            }
            assertArrayEquals(beforeWal, Files.readAllBytes(wal));
        }
    }

    @Test void changingOnlyV3MetadataDoesNotMakeItsLayoutCompatible() throws Exception {
        Path file = fixture("forged.sqlite", UnaryOperator.identity(), 3);
        try (var connection = connect(file)) { sql(connection, "UPDATE economy_schema_migrations SET version=6"); }
        assertRejectedUnchanged(file);
    }

    @ParameterizedTest @ValueSource(strings = {"ACTIVE", "PAUSED", "SETTLING_COMPLETED", "SETTLING_ABORTED", "COMPLETED", "ABORTED"})
    void oldV4WorldIsRejectedWithoutFillingIdentityOrStaffingFromNpcSnapshots(String status) throws Exception {
        Path file = fixture("v4.sqlite", UnaryOperator.identity(), 4);
        String oldJson = V6_BATCH_JSON.replace("\"formatVersion\":6", "\"formatVersion\":4");
        seedBatch(file, oldJson, status, status.endsWith("COMPLETED") ? 200 : 199);
        if (status.equals("COMPLETED") || status.equals("ABORTED"))
            try (var connection = connect(file)) { sql(connection, "UPDATE production_executions SET boundary='METHODS'"); }
        assertRejectedUnchanged(file);
        try (var reader = readOnly(file)) {
            assertEquals(4, scalar(reader, "SELECT version FROM economy_schema_migrations"));
            assertEquals(0, scalar(reader, "SELECT COUNT(*) FROM sqlite_master WHERE name IN ('companies','regions','building_staffing')"));
        }
    }

    @Test void oldV4MainAndWalBytesSurviveTheNewIdentityGate() throws Exception {
        Path file = fixture("v4-wal.sqlite", UnaryOperator.identity(), 4);
        seedBatch(file, V6_BATCH_JSON.replace("\"formatVersion\":6", "\"formatVersion\":4"), "ACTIVE", 100);
        try (var writer = connect(file)) {
            sql(writer, "PRAGMA journal_mode=WAL"); sql(writer, "PRAGMA wal_autocheckpoint=0");
            sql(writer, "UPDATE production_batches SET status='PAUSED'");
            Path wal = Path.of(file + "-wal");
            byte[] before = Files.readAllBytes(wal);
            assertTrue(before.length > 0);
            assertRejectedUnchanged(file);
            assertArrayEquals(before, Files.readAllBytes(wal));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ACTIVE", "PAUSED", "SETTLING_COMPLETED", "SETTLING_ABORTED", "COMPLETED", "ABORTED"})
    void oldV5WorldAndItsIntegerSnapshotsAreRejectedWithoutAddingMoneyOrPayroll(String status) throws Exception {
        Path file = fixture("v5.sqlite", UnaryOperator.identity(), 5);
        seedBatch(file, V6_BATCH_JSON.replace("\"formatVersion\":6", "\"formatVersion\":5"), status, status.endsWith("COMPLETED") ? 200 : 199);
        assertRejectedUnchanged(file);
        try (var reader = readOnly(file)) {
            assertEquals(5, scalar(reader, "SELECT version FROM economy_schema_migrations"));
            assertEquals(0, scalar(reader, "SELECT COUNT(*) FROM sqlite_master WHERE name IN ('economic_time','finance_accounts','payroll_accruals')"));
        }
    }

    @Test void oldV5MainAndWalRemainUnchangedOnV6Rejection() throws Exception {
        Path file = fixture("v5-wal.sqlite", UnaryOperator.identity(), 5);
        try (var writer = connect(file)) {
            sql(writer, "PRAGMA journal_mode=WAL"); sql(writer, "PRAGMA wal_autocheckpoint=0");
            sql(writer, "UPDATE economic_buildings SET status='STOPPED'");
            byte[] wal = Files.readAllBytes(Path.of(file + "-wal"));
            assertTrue(wal.length > 0); assertRejectedUnchanged(file);
            assertArrayEquals(wal, Files.readAllBytes(Path.of(file + "-wal")));
        }
    }

    @ParameterizedTest @ValueSource(strings={"ACTIVE","PAUSED","SETTLING_COMPLETED","SETTLING_ABORTED","COMPLETED","ABORTED"})
    void oldV6NpcSnapshotsAndLegacyBoundaryAreRejectedWithoutReinterpretation(String status)throws Exception{
        Path file=fixture("v6-historical.sqlite",UnaryOperator.identity(),6);
        seedBatch(file,V6_BATCH_JSON,status,status.endsWith("COMPLETED")?200:199);
        try(var c=connect(file)){sql(c,"UPDATE production_executions SET boundary='"+((status.equals("COMPLETED")||status.equals("ABORTED"))?"WORKFORCE":"NONE")+"'");}
        assertRejectedUnchanged(file);
        try(var c=readOnly(file)){
            assertEquals(6,scalar(c,"SELECT version FROM economy_schema_migrations"));assertEquals(1,scalar(c,"SELECT COUNT(*) FROM sqlite_master WHERE name='employment_state'"));
            try(var s=c.createStatement();var r=s.executeQuery("SELECT configuration_json FROM production_batches")){r.next();assertEquals(V6_BATCH_JSON,r.getString(1));}
        }
    }
    @Test void oldV6MainAndWalBytesSurviveTheIntegerStaffingGate()throws Exception{
        Path file=fixture("v6-historical-wal.sqlite",UnaryOperator.identity(),6);seedBatch(file,V6_BATCH_JSON,"PAUSED",199);
        try(var writer=connect(file)){
            sql(writer,"PRAGMA journal_mode=WAL");sql(writer,"PRAGMA wal_autocheckpoint=0");sql(writer,"UPDATE production_executions SET automatic=1");
            Path wal=Path.of(file+"-wal");byte[] before=Files.readAllBytes(wal);assertTrue(before.length>0);assertRejectedUnchanged(file);assertArrayEquals(before,Files.readAllBytes(wal));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missingStaffing", "invalidIdentity", "invalidCount", "overSupply"})
    void newIdentityAndLaborInconsistenciesAreRejectedBeforeBusinessWrites(String kind) throws Exception {
        Path file = fixture("invalid-v8.sqlite");
        try (var connection = connect(file)) {
            switch (kind) {
                case "missingStaffing" -> sql(connection, "DELETE FROM building_staffing");
                case "invalidIdentity" -> {
                    sql(connection, "PRAGMA foreign_keys=OFF");
                    sql(connection, "UPDATE companies SET id='free-form-company'");
                    sql(connection, "UPDATE economic_buildings SET owner_company_id='free-form-company'");
                }
                case "invalidCount" -> {
                    sql(connection, "INSERT INTO occupation_catalog VALUES('worker')");
                    sql(connection, "PRAGMA ignore_check_constraints=ON");
                    sql(connection, "INSERT INTO building_staffing_roles VALUES('" + BUILDING + "','worker',1.5,NULL)");
                }
                default -> {
                    sql(connection, "INSERT INTO occupation_catalog VALUES('worker')");
                    sql(connection, "INSERT INTO building_staffing_roles VALUES('" + BUILDING + "','worker',1,NULL)");
                }
            }
        }
        assertRejectedUnchanged(file);
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

    @Test void absentDatabaseCreatesV8AndExplicitSnapshotEnvelopes() throws Exception {
        Path file = directory.resolve("new.sqlite");
        assertFalse(Files.exists(file));
        try (var connection = SqliteEconomyDatabase.open(file)) {
            assertEquals(8, scalar(connection, "SELECT version FROM economy_schema_migrations"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM sqlite_master WHERE name='equipment_holdings'"));
            var store = new SqliteInventoryStore(connection, new Object());
            store.createLocation(BUILDING, 100);
            store.deposit(BUILDING, Map.of("ore", 12L));
            assertEquals(12L, store.inspect(BUILDING).quantities().get("ore"));
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT state_json FROM inventory_locations")) {
                assertTrue(rows.next());
                assertEquals(Set.of("formatVersion", "snapshot"),
                        keys(connection, rows.getString(1)));
            }
            assertEquals(Set.of("formatVersion", "fullSpeedTicks", "snapshot"),
                    keys(connection, EconomyStateCodec.encodeBatchConfiguration(EconomyStateCodec.decodeBatchConfiguration(BATCH_JSON))));
            try (var statement = connection.prepareStatement("SELECT json_extract(?, '$.snapshot')")) {
                statement.setString(1, EconomyStateCodec.encodeBatchConfiguration(EconomyStateCodec.decodeBatchConfiguration(BATCH_JSON)));
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(Set.of("productionMethodSelections", "resolvedRecipe", "workforcePlan", "effectiveRevision"), keys(connection, rows.getString(1)));
                }
            }
        }
    }

    @Test void quantitiesAboveDoublePrecisionRemainExactLongValuesAcrossRestart() throws Exception {
        Path file = fixture("fractions.sqlite");
        try (var connection = SqliteEconomyDatabase.open(file)) {
            var store = new SqliteInventoryStore(connection, new Object());
            store.createLocation(BATCH, Long.MAX_VALUE);
            store.deposit(BATCH, Map.of("wood", 9007199254740993L));
        }
        try (var connection = SqliteEconomyDatabase.open(file)) {
            assertEquals(9007199254740993L, new SqliteInventoryStore(connection, new Object()).inspect(BATCH).quantities().get("wood"));
        }
        try (var connection = connect(file)) { sql(connection, "UPDATE economy_schema_migrations SET version=2"); }
        assertRejectedUnchanged(file);
    }

    @Test void floatingPointSignedZeroIsRejectedInV8WithoutConversion() throws Exception {
        Path file = fixture("signed-zero.sqlite");
        try (var connection = connect(file)) {
            updateInventory(connection, INVENTORY_JSON.replace("\"metal\":1", "\"metal\":-0.0"));
        }
        assertRejectedUnchanged(file);
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
        return fixture(name, change, 8);
    }

    @ParameterizedTest @ValueSource(strings={"ACTIVE","PAUSED","SETTLING_COMPLETED","SETTLING_ABORTED","COMPLETED","ABORTED"})
    void oldV7IntegerStaffingWorldIsRejectedWithoutChangingAnyBytes(String status)throws Exception{
        Path file=fixture("historical-v7.sqlite",UnaryOperator.identity(),7);
        seedBatch(file,BATCH_JSON.replace("\"formatVersion\":8","\"formatVersion\":7"),status,status.endsWith("COMPLETED")?200:199);
        assertRejectedUnchanged(file);
        try(var c=readOnly(file)){assertEquals(7,scalar(c,"SELECT version FROM economy_schema_migrations"));assertEquals(0,scalar(c,"SELECT COUNT(*) FROM sqlite_master WHERE name='payroll_payment_runs'"));}
    }
    @Test void oldV7WalIsRejectedWithoutCheckpointOrRewrite()throws Exception{
        Path file=fixture("historical-v7-wal.sqlite",UnaryOperator.identity(),7);
        seedBatch(file,BATCH_JSON.replace("\"formatVersion\":8","\"formatVersion\":7"),"PAUSED",199);
        try(var writer=connect(file)){
            sql(writer,"PRAGMA journal_mode=WAL");sql(writer,"PRAGMA wal_autocheckpoint=0");sql(writer,"UPDATE production_executions SET automatic=1");
            Path wal=Path.of(file+"-wal");byte[] main=Files.readAllBytes(file),beforeWal=Files.readAllBytes(wal);
            assertRejectedUnchanged(file);assertArrayEquals(main,Files.readAllBytes(file));assertArrayEquals(beforeWal,Files.readAllBytes(wal));
        }
    }

    private Path legacyFixture(String name) throws Exception { return fixture(name, UnaryOperator.identity(), 2); }

    private Path fixture(String name, UnaryOperator<String> change, int version) throws Exception {
        Path file = directory.resolve(name);
        String ddl;
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/sqlite/schema-v" + version + ".sql"))) {
            ddl = change.apply(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (var connection = connect(file)) {
            for (String command : ddl.split(";")) if (!command.isBlank()) sql(connection, command);
            if (version >= 5) com.bbmurloc.victoriaeconomics.server.TestIdentities.building(connection, BUILDING, "factory");
            else sql(connection, "INSERT INTO economic_buildings VALUES('" + BUILDING + "','factory','ACTIVE')");
            sql(connection, "INSERT INTO production_method_configurations VALUES('" + BUILDING + "','{\"methods\":{\"base\":\"a\"}}',NULL,0,0)");
            sql(connection, "INSERT INTO production_executions VALUES('" + BUILDING + "',NULL,NULL,'NONE',0,0,0" + (version >= 7 ? ",NULL,'[]'" : "") + ")");
            try (var insert = connection.prepareStatement(version == 2 ? "INSERT INTO inventory_locations VALUES(?,?)" : "INSERT INTO inventory_locations VALUES(?,?,0)")) {
                insert.setString(1, BUILDING.toString());
                insert.setString(2, version == 2 ? LEGACY_INVENTORY_JSON : INVENTORY_JSON.replace("\"formatVersion\":8", "\"formatVersion\":" + version));
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
            long version=scalar(connection,"SELECT version FROM economy_schema_migrations");
            if(version>=7){
                sql(connection,"UPDATE production_executions SET pause_reasons_json='"+(status.equals("PAUSED")?"[\"MANUAL\"]":"[]")+"'");
                if(status.equals("COMPLETED")||status.equals("ABORTED"))sql(connection,"UPDATE production_executions SET boundary='RECONFIGURING'");
                // Explicit consistent historical participation fixture; never inferred from a UUID list.
                sql(connection,"INSERT INTO occupation_catalog VALUES('worker') ON CONFLICT DO NOTHING");
                sql(connection,"INSERT INTO regional_labor_supply VALUES('"+com.bbmurloc.victoriaeconomics.server.TestIdentities.REGION+"','worker',1,0) ON CONFLICT DO NOTHING");
                sql(connection,"INSERT INTO building_staffing_roles VALUES('"+BUILDING+"','worker',1,NULL) ON CONFLICT DO NOTHING");
                sql(connection,"UPDATE payroll_accruals SET employees=1");sql(connection,"UPDATE payroll_employment_history SET employees=1");
            }
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
