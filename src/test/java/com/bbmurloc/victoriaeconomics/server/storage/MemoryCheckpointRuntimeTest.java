package com.bbmurloc.victoriaeconomics.server.storage;

import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryCheckpointRuntimeTest {
    @TempDir Path world;
    Path file() { return world.resolve("victoria_economics/economy.sqlite"); }

    @Test void committedBusinessIsVisibleInOneMemoryAuthorityButNotInOlderDiskCheckpoint() throws Exception {
        UUID location = UUID.randomUUID();
        try (var runtime = EconomyDatabase.open(world)) {
            byte[] before = Files.readAllBytes(file());
            var stock = new SqliteInventoryStore(runtime.getConnection(), new Object());
            stock.createLocation(location, 100); stock.deposit(location, Map.of("iron", 64L));
            try (var verifier = runtime.openVerificationConnection()) {
                assertEquals(64, new SqliteInventoryStore(verifier, new Object()).inspect(location).quantities().get("iron"));
                try (var s = verifier.createStatement(); var r = s.executeQuery("PRAGMA database_list")) {
                    assertTrue(r.next()); assertEquals("", r.getString("file"));
                }
            }
            assertArrayEquals(before, Files.readAllBytes(file()), "Business commits must not fsync the recovery file");
            try (var disk = SqliteEconomyDatabase.openReadOnly(file()); var s = disk.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM inventory_locations")) {
                assertTrue(r.next()); assertEquals(0, r.getLong(1));
            }
            EconomicCheckpointWriter.await(runtime.requestCheckpoint());
            assertEquals(2, runtime.checkpointStatus().committed().sequence());
        }
        try (var runtime = EconomyDatabase.open(world)) {
            assertEquals(64, new SqliteInventoryStore(runtime.getConnection(), new Object()).inspect(location).quantities().get("iron"));
        }
    }

    @Test void shutdownSavesUnsavedInventoryAndRestartDoesNotInventTime() {
        UUID id = UUID.randomUUID();
        try (var runtime = EconomyDatabase.open(world)) {
            var stock = new SqliteInventoryStore(runtime.getConnection(), new Object());
            stock.createLocation(id, 99); stock.deposit(id, Map.of("tools", 23L));
        }
        try (var runtime = EconomyDatabase.open(world)) {
            assertEquals(0, runtime.checkpointStatus().committed().economicTick());
            assertEquals(23, new SqliteInventoryStore(runtime.getConnection(), new Object()).inspect(id).quantities().get("tools"));
        }
    }

    @Test void sameWorldCannotHaveTwoLiveMemoryAuthorities() {
        try (var first = EconomyDatabase.open(world)) {
            assertThrows(IllegalStateException.class, () -> EconomyDatabase.open(world));
        }
        try (var second = EconomyDatabase.open(world)) { assertNotNull(second.getConnection()); }
    }

    @Test void multiDomainMemoryTransactionRollsBackAsAWhole() {
        try (var runtime = EconomyDatabase.open(world)) {
            var c = runtime.getConnection(); UUID company = UUID.randomUUID(), region = UUID.randomUUID();
            assertThrows(IllegalStateException.class, () -> SqliteTransactions.write(c, tx -> {
                try (var s = c.createStatement()) {
                    s.executeUpdate("INSERT INTO companies VALUES('" + company + "')");
                    s.executeUpdate("INSERT INTO regions VALUES('" + region + "')");
                }
                throw new IllegalStateException("second-domain failure");
            }));
            try (var verify = runtime.openVerificationConnection(); var s = verify.createStatement(); var r = s.executeQuery("SELECT (SELECT COUNT(*) FROM companies)+(SELECT COUNT(*) FROM regions)")) {
                assertTrue(r.next()); assertEquals(0, r.getLong(1));
            } catch (SQLException failure) { throw new AssertionError(failure); }
        }
    }

    @Test void unresolvedLocalTransactionCannotBeCapturedOrPublishedAsSave() throws Exception {
        var runtime = EconomyDatabase.open(world);
        try {
            runtime.getConnection().setAutoCommit(false);
            assertThrows(CommitOutcomeUnknownException.class, runtime::requestCheckpoint);
            assertThrows(IllegalStateException.class, runtime::awaitLatestCheckpoint, "Previous save cannot acknowledge a rejected capture");
            runtime.getConnection().rollback(); runtime.getConnection().setAutoCommit(true);
            assertEquals(1, runtime.checkpointStatus().committed().sequence());
        } finally { runtime.close(); }
    }

    @Test void snapshotDoesNotFollowSubsequentMutableInventoryChanges() throws Exception {
        try (var runtime = EconomyDatabase.open(world)) {
            var stock = new SqliteInventoryStore(runtime.getConnection(), new Object()); var id = UUID.randomUUID();
            stock.createLocation(id, 100); stock.deposit(id, Map.of("iron", 1L));
            var frozen = EconomyCheckpoint.capture(runtime.getConnection());
            stock.deposit(id, Map.of("iron", 10L));
            assertNotEquals(frozen.sha256(), EconomyCheckpoint.capture(runtime.getConnection()).sha256());
            frozen.validate(); // still valid independently of live state
        }
    }

    @Test void queueHasOnePendingImageAndMergedWaitersOnlyCompleteAfterRealCommit() throws Exception {
        try (var runtime = EconomyDatabase.open(world)) {
            var stock = new SqliteInventoryStore(runtime.getConnection(), new Object()); var id = UUID.randomUUID();
            stock.createLocation(id, 100);
            var a = EconomyCheckpoint.capture(runtime.getConnection());
            stock.deposit(id, Map.of("iron", 1L)); var b = EconomyCheckpoint.capture(runtime.getConnection());
            stock.deposit(id, Map.of("iron", 1L)); var c = EconomyCheckpoint.capture(runtime.getConnection());
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var writes = new ArrayList<String>();
            try (var writer = new EconomicCheckpointWriter(runtime.checkpointStatus().committed(), (prior, next, snapshot) -> {
                synchronized (writes) { writes.add(snapshot.sha256()); }
                if (writes.size() == 1) {
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("test release missing"); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                }
                return next;
            })) {
                var first = writer.submit(a); assertTrue(entered.await(10, TimeUnit.SECONDS));
                var replaced = writer.submit(b); var last = writer.submit(c);
                assertFalse(first.isDone()); assertFalse(replaced.isDone()); assertFalse(last.isDone());
                assertNotNull(writer.status().inFlightSequence()); assertNotNull(writer.status().pendingSequence());
                assertEquals(1, writer.status().coalesced());
                release.countDown();
                assertEquals(EconomicCheckpointWriter.await(last), EconomicCheckpointWriter.await(replaced));
                EconomicCheckpointWriter.await(first);
                assertEquals(List.of(a.sha256(), c.sha256()), writes);
            } finally { release.countDown(); }
        }
    }

    @Test void unknownWriterOutcomePoisonsFutureSavesInsteadOfBlindRetry() {
        try (var runtime = EconomyDatabase.open(world)) {
            var c = runtime.getConnection(); new SqliteInventoryStore(c, new Object()).createLocation(UUID.randomUUID(), 1);
            try (var writer = new EconomicCheckpointWriter(runtime.checkpointStatus().committed(), (prior, next, snapshot) -> {
                throw new CommitOutcomeUnknownException("unverifiable disk", null);
            })) {
                assertThrows(IllegalStateException.class, () -> EconomicCheckpointWriter.await(writer.submit(EconomyCheckpoint.capture(c))));
                assertTrue(writer.status().blocked()); assertEquals(1, writer.status().failed());
                assertThrows(IllegalStateException.class, () -> writer.submit(EconomyCheckpoint.capture(c)));
            }
        }
    }

    @Test void incompleteAcceptedTickCannotReplaceLastSave() throws Exception {
        var runtime = EconomyDatabase.open(world);
        try {
            try (var s = runtime.getConnection().createStatement()) { s.executeUpdate("UPDATE economic_time SET accepted_tick=1"); }
            assertThrows(IllegalStateException.class, runtime::requestCheckpoint);
            assertEquals(0, new SqliteCheckpointStore(file()).load().receipt().economicTick());
            try (var s = runtime.getConnection().createStatement()) { s.executeUpdate("UPDATE economic_time SET accepted_tick=0"); }
        } finally { runtime.close(); }
    }

    @Test void unknownMemoryCommitIsVerifiedInRamWithoutReadingStaleDiskOrSavingUntrustedHandle() throws Exception {
        var runtime = EconomyDatabase.open(world); var company = UUID.randomUUID();
        var faults = new com.bbmurloc.victoriaeconomics.server.financial.JdbcFaults(runtime.getConnection());
        faults.afterCommit = true;
        var unknown = assertThrows(CommitOutcomeUnknownException.class, () -> SqliteTransactions.write(faults.connection, tx -> {
            try (var s = faults.connection.createStatement()) { s.executeUpdate("INSERT INTO companies VALUES('" + company + "')"); }
            return null;
        }));
        SqliteConnectionGuard.quarantine(faults.connection, unknown);
        try (var fresh = runtime.openVerificationConnection(); var s = fresh.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM companies")) {
            assertTrue(r.next()); assertEquals(1, r.getLong(1), "Keeper preserves the actual current-runtime commit");
        }
        assertThrows(CommitOutcomeUnknownException.class, runtime::requestCheckpoint);
        assertThrows(RuntimeException.class, runtime::close);
        try (var restarted = EconomyDatabase.open(world); var s = restarted.getConnection().createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM companies")) {
            assertTrue(r.next()); assertEquals(0, r.getLong(1), "Crash/reopen may lose the entire unsaved suffix");
        }
    }

    @Test void sharedMemoryWritePermissionPrecedesReadsAndExcludesASecondWriter() throws Exception {
        try (var runtime = EconomyDatabase.open(world); var second = runtime.openVerificationConnection()) {
            SqliteTransactions.write(runtime.getConnection(), tx -> {
                assertThrows(RuntimeException.class, () -> SqliteTransactions.write(second, other -> {
                    fail("Second writer entered authoritative work before the first released IMMEDIATE"); return null;
                }));
                return null;
            });
            assertEquals(1, runtime.checkpointStatus().committed().sequence());
        }
    }
}
