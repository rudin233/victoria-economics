package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.financial.JdbcFaults;
import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.EconomyCheckpoint.Receipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckpointPersistenceTest {
    @TempDir Path directory;
    Path file() { return directory.resolve("checkpoint.sqlite"); }
    Connection memory() throws SQLException {
        var c = SqliteConnectionGuard.protect(DriverManager.getConnection("jdbc:sqlite::memory:")); EconomySchema.initialize(c); return c;
    }
    Receipt next(Receipt prior, EconomyCheckpoint image) {
        return new Receipt(prior == null ? UUID.randomUUID() : prior.worldId(), UUID.randomUUID(), prior == null ? 1 : prior.sequence() + 1, image.economicTick(), image.sha256());
    }

    @ParameterizedTest @ValueSource(strings = {"DELETE-before", "DELETE-after", "WAL-before", "WAL-after"})
    void realProcessDeathRecoversOnlyTheOldOrNewWholeImage(String scenario) throws Exception {
        String[] parts = scenario.split("-");
        try (var c = memory()) {
            var store = new SqliteCheckpointStore(file()); var old = EconomyCheckpoint.capture(c); var first = store.save(null, next(null, old), old);
            try (var disk = SqliteEconomyDatabase.open(file()); var s = disk.createStatement()) { s.execute("PRAGMA journal_mode=" + parts[0]); }
            var output = directory.resolve("crash-child.log");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-cp",
                    System.getProperty("ve.test.runtimeClasspath"), CheckpointCrashProcess.class.getName(), parts[1], file().toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "Crash child did not exit");
            assertEquals(parts[1].equals("before") ? 71 : 72, process.exitValue(), Files.readString(output));
            if (scenario.equals("DELETE-before")) {
                assertTrue(Files.size(Path.of(file() + "-journal")) > 512, "No real rollback journal was produced");
                assertThrows(IllegalStateException.class, () -> SqliteEconomyDatabase.openReadOnly(file()), "Expected hot journal must require isolated-copy recovery");
            }
            byte[] mainBefore = Files.readAllBytes(file());
            var recovered = store.load(); recovered.snapshot().validate();
            assertArrayEquals(mainBefore, Files.readAllBytes(file()), "Recovery preflight may only recover a private copy");
            if (parts[1].equals("before")) assertEquals(first, recovered.receipt());
            else assertEquals(first.sequence() + 1, recovered.receipt().sequence());
            // The next real save can recover the verified current-format hot journal and commit normally.
            var confirmed = store.save(recovered.receipt(), next(recovered.receipt(), recovered.snapshot()), recovered.snapshot());
            assertEquals(confirmed, store.load().receipt());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"DELETE", "WAL"})
    void wholeSnapshotAndMetadataSurviveReopenInBothJournalModes(String journal) throws Exception {
        try (var c = memory()) {
            var store = new SqliteCheckpointStore(file()); var initial = EconomyCheckpoint.capture(c);
            var first = store.save(null, next(null, initial), initial);
            try (var disk = SqliteEconomyDatabase.open(file()); var s = disk.createStatement()) { s.execute("PRAGMA journal_mode=" + journal); }
            var stock = new SqliteInventoryStore(c, new Object()); var id = UUID.randomUUID();
            stock.createLocation(id, 100); stock.deposit(id, Map.of("iron", 64L));
            var image = EconomyCheckpoint.capture(c); var receipt = store.save(first, next(first, image), image);
            var recovered = store.load(); assertEquals(receipt, recovered.receipt()); assertEquals(image.sha256(), recovered.snapshot().sha256());
            recovered.snapshot().validate();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"DELETE", "WAL"})
    void failureAfterEarlierDomainWritesRollsBackEverythingAndKeepsPriorGeneration(String journal) throws Exception {
        try (var c = memory()) {
            var store = new SqliteCheckpointStore(file()); var old = EconomyCheckpoint.capture(c); var first = store.save(null, next(null, old), old);
            try (var disk = SqliteEconomyDatabase.open(file()); var s = disk.createStatement()) { s.execute("PRAGMA journal_mode=" + journal); }
            new SqliteInventoryStore(c, new Object()).createLocation(UUID.randomUUID(), 200);
            var image = EconomyCheckpoint.capture(c);
            var failing = new SqliteCheckpointStore(file(), () -> {
                var faults = new JdbcFaults(SqliteEconomyDatabase.open(file()));
                faults.beforeSql = sql -> { if (sql.startsWith("INSERT INTO \"payroll_runtime\"")) throw new IllegalStateException("late snapshot failure"); };
                return faults.connection;
            });
            assertThrows(IllegalStateException.class, () -> failing.save(first, next(first, image), image));
            assertEquals(first, store.load().receipt()); assertEquals(old.sha256(), store.load().snapshot().sha256());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"DELETE", "WAL"})
    void lostCommitReceiptIsVerifiedWithFreshConnectionWithoutSecondCommit(String journal) throws Exception {
        try (var c = memory()) {
            var store = new SqliteCheckpointStore(file()); var old = EconomyCheckpoint.capture(c); var first = store.save(null, next(null, old), old);
            try (var disk = SqliteEconomyDatabase.open(file()); var s = disk.createStatement()) { s.execute("PRAGMA journal_mode=" + journal); }
            new SqliteInventoryStore(c, new Object()).createLocation(UUID.randomUUID(), 1);
            var image = EconomyCheckpoint.capture(c); var expected = next(first, image); JdbcFaults[] injected = new JdbcFaults[1];
            var failing = new SqliteCheckpointStore(file(), () -> {
                var faults = new JdbcFaults(SqliteEconomyDatabase.open(file())); faults.afterCommit = true; injected[0] = faults; return faults.connection;
            });
            assertEquals(expected, failing.save(first, expected, image)); assertEquals(1, injected[0].commits);
            assertEquals(expected, store.load().receipt());
            var retired = assertThrows(SQLException.class, () -> injected[0].connection.createStatement());
            assertTrue(retired.getMessage().contains("quarantined"));
            assertInstanceOf(CommitOutcomeUnknownException.class, retired.getCause());
        }
    }

    @Test void preCommitFailureIsNotAutomaticallyRetriedOrReportedAsSaved() throws Exception {
        try (var c = memory()) {
            var store = new SqliteCheckpointStore(file()); var old = EconomyCheckpoint.capture(c); var first = store.save(null, next(null, old), old);
            new SqliteInventoryStore(c, new Object()).createLocation(UUID.randomUUID(), 1);
            var image = EconomyCheckpoint.capture(c); JdbcFaults[] injected = new JdbcFaults[1];
            var failing = new SqliteCheckpointStore(file(), () -> {
                var faults = new JdbcFaults(SqliteEconomyDatabase.open(file())); faults.beforeCommit = true; injected[0] = faults; return faults.connection;
            });
            assertThrows(IllegalStateException.class, () -> failing.save(first, next(first, image), image));
            assertEquals(1, injected[0].commits); assertEquals(first, store.load().receipt());
        }
    }

    @Test void diskCasRejectsAStaleSnapshotWriter() throws Exception {
        try (var c = memory()) {
            var store = new SqliteCheckpointStore(file()); var image = EconomyCheckpoint.capture(c); var first = store.save(null, next(null, image), image);
            var second = store.save(first, next(first, image), image);
            assertThrows(IllegalStateException.class, () -> store.save(first, next(first, image), image));
            assertEquals(second, store.load().receipt());
        }
    }

    @Test void validBusinessTamperingStillFailsFullCheckpointDigestWithoutRewritingBytes() throws Exception {
        try (var c = memory()) {
            var id = UUID.randomUUID(); var stock = new SqliteInventoryStore(c, new Object()); stock.createLocation(id, 100); stock.deposit(id, Map.of("iron", 1L));
            var image = EconomyCheckpoint.capture(c); var store = new SqliteCheckpointStore(file()); store.save(null, next(null, image), image);
            try (var disk = SqliteEconomyDatabase.open(file()); var s = disk.createStatement()) { s.executeUpdate("UPDATE inventory_locations SET state_json=replace(state_json,'\"iron\":1','\"iron\":2')"); }
            var before = Files.readAllBytes(file()); assertThrows(IllegalStateException.class, store::load); assertArrayEquals(before, Files.readAllBytes(file()));
        }
    }

    @Test void literalV9AndItsWalAreRejectedBeforeWritableRecoveryOpen() throws Exception {
        String ddl;
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/sqlite/schema-v9.sql"))) { ddl = new String(resource.readAllBytes(), StandardCharsets.UTF_8); }
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file()); var s = c.createStatement()) {
            for (String command : ddl.split(";")) if (!command.isBlank()) s.execute(command);
            s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA wal_autocheckpoint=0"); s.executeUpdate("INSERT INTO companies VALUES('" + UUID.randomUUID() + "')");
            byte[] before = Files.readAllBytes(file()), wal = Files.readAllBytes(Path.of(file() + "-wal"));
            assertThrows(IllegalStateException.class, () -> new SqliteCheckpointStore(file()).load());
            assertArrayEquals(before, Files.readAllBytes(file())); assertArrayEquals(wal, Files.readAllBytes(Path.of(file() + "-wal")));
        }
    }

    @Test void checkpointPayloadCannotUseAnotherConnectionsWriteCapability() throws Exception {
        try(var first=memory();var second=memory()){
            var image=EconomyCheckpoint.capture(first);String before=EconomyCheckpoint.capture(second).sha256();
            assertThrows(IllegalStateException.class,()->SqliteTransactions.write(first,tx->{
                assertThrows(IllegalArgumentException.class,()->image.install(second,tx));return null;
            }));
            assertEquals(before,EconomyCheckpoint.capture(second).sha256());
        }
    }

    @Test void checkpointReceiptCannotUseAnotherConnectionsWriteCapability() throws Exception {
        try(var first=memory();var second=memory()){
            var receipt=next(null,EconomyCheckpoint.capture(first));
            assertThrows(IllegalStateException.class,()->SqliteTransactions.write(first,tx->{
                assertThrows(IllegalArgumentException.class,()->EconomyCheckpoint.saveReceipt(second,tx,receipt));return null;
            }));
            assertTrue(EconomyCheckpoint.receipt(second).isEmpty());
        }
    }
}
