package com.bbmurloc.victoriaeconomics.server.workforce.infrastructure;

import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.production.application.StaffingCalculator;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.workforce.LaborTestWorld;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class LaborTransactionSafetyTest {
    @TempDir Path directory;
    private Path file() { return directory.resolve("labor.sqlite"); }

    @Test void unknownCreationCannotPublishAPartialPersistedGraph() throws Exception {
        var reference = new AtomicReference<Faults>();
        try (var w = world(reference)) {
            reference.get().failCommitAfter = true;
            var unknown = assertThrows(BuildingCreationUnconfirmed.class, w::create);
            // Deliberately corrupt only this temporary fixture after the durable commit.
            w.sql("DELETE FROM building_staffing");
            assertThrows(IllegalStateException.class, () -> w.buildings.verifyCreation(unknown.candidate()));
            assertTrue(w.registry.getAll().isEmpty());
        }
    }

    @Test void staleSnapshotsFromAnotherConnectionFailAllThreeCasChecks() throws Exception {
        try (var first = new LaborTestWorld(file()); var second = new LaborTestWorld(file())) {
            UUID id = first.create().getId(); first.setSupply(10);
            var building = second.identities.load(id); var staffing = second.staffing.load(id);
            var supply = second.supply.find(TestIdentities.REGION, "laborer").orElseThrow();
            first.buildings.stopBuilding(id); first.commands.requestTarget(id, "laborer", 3); first.setSupply(9);
            assertThrows(IllegalStateException.class, () -> second.identities.save(building.withStatus(BuildingStatus.DISABLED), 0));
            assertThrows(IllegalStateException.class, () -> SqliteTransactions.write(second.connection, tx -> {
                second.staffing.inTransaction(tx).save(staffing.requestTarget("laborer", 4, Map.of("laborer", 10)), 0); return null;
            }));
            assertThrows(IllegalStateException.class, () -> SqliteTransactions.write(second.connection, tx -> {
                second.supply.inTransaction(tx).save(supply.withTotal(8), 0); return null;
            }));
            assertEquals(1, first.identities.load(id).identityRevision());
            assertEquals(Map.of("laborer", 3), first.staffing.load(id).pending());
            assertEquals(9, first.supply.find(TestIdentities.REGION, "laborer").orElseThrow().totalSupply());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void isolatedEmploymentCommitUncertaintyPreservesOneWholePersistentOutcome(boolean actuallyCommitted) throws Exception {
        var reference = new AtomicReference<Faults>(); UUID id;
        try (var w = world(reference)) {
            id = w.create().getId(); w.setSupply(10); w.commands.requestTarget(id, "laborer", 8);
            var faults = reference.get(); faults.commits = 0;
            faults.failCommitBefore = !actuallyCommitted; faults.failCommitAfter = actuallyCommitted;
            assertThrows(CommitOutcomeUnknownException.class, () -> w.applyPendingForPersistenceTest(id));
            assertEquals(1, faults.commits);
        }
        try (var fresh = new LaborTestWorld(file())) {
            var root = fresh.staffing.load(id);
            assertEquals(actuallyCommitted ? Map.of("laborer", 8) : Map.of(), root.employed());
            assertEquals(actuallyCommitted ? Map.of() : Map.of("laborer", 8), root.pending());
            assertEquals(actuallyCommitted ? 8 : 0, fresh.supply.occupied(TestIdentities.REGION, "laborer"));
            assertEquals(actuallyCommitted ? 2 : 1, root.revision());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"DELETE", "WAL"})
    void actualWritePermissionPrecedesIdentityAndStaffingReads(String mode) throws Exception {
        var reference = new AtomicReference<Faults>();
        try (var w = world(reference); var observer = DriverManager.getConnection("jdbc:sqlite:" + file())) {
            UUID id = w.create().getId(); w.setSupply(10); w.sql("PRAGMA journal_mode=" + mode);
            sql(observer, "PRAGMA busy_timeout=0");
            var observed = new AtomicBoolean();
            reference.get().beforeSql = text -> {
                if (text.startsWith("SELECT * FROM economic_buildings WHERE") && observed.compareAndSet(false, true)) {
                    var busy = assertThrows(SQLException.class, () -> sql(observer, "UPDATE regional_labor_supply SET revision=revision"));
                    assertEquals(5, busy.getErrorCode());
                }
            };
            w.commands.requestTarget(id, "laborer", 4);
            assertTrue(observed.get());
            assertEquals(Map.of("laborer", 4), w.staffing.load(id).pending());
        }
    }
    @Test void twoConnectionsCompeteForOneRealRegionalSupplyWithoutOversubscription() throws Exception {
        try (var first = new LaborTestWorld(file()); var second = new LaborTestWorld(file()); var pool = Executors.newFixedThreadPool(2)) {
            first.sql("PRAGMA journal_mode=WAL");
            UUID a = first.create().getId(), b = first.create().getId(); first.setSupply(12);
            first.commands.requestTarget(a, "laborer", 8); second.commands.requestTarget(b, "laborer", 8);
            var barrier = new CyclicBarrier(2);
            var x = pool.submit(() -> { barrier.await(); first.applyPendingForPersistenceTest(a); return null; });
            var y = pool.submit(() -> { barrier.await(); second.applyPendingForPersistenceTest(b); return null; });
            x.get(10, TimeUnit.SECONDS); y.get(10, TimeUnit.SECONDS);
            int aCount = first.staffing.load(a).employed().get("laborer"), bCount = second.staffing.load(b).employed().get("laborer");
            assertEquals(Set.of(8, 4), Set.of(aCount, bCount));
            assertEquals(12, first.supply.occupied(TestIdentities.REGION, "laborer"));
            assertTrue(first.staffing.load(a).pending().isEmpty()); assertTrue(second.staffing.load(b).pending().isEmpty());
        }
    }
    @Test void concurrentSupplyReductionAndHiringCannotViolateTheConstraint() throws Exception {
        try (var first = new LaborTestWorld(file()); var second = new LaborTestWorld(file()); var pool = Executors.newFixedThreadPool(2)) {
            first.sql("PRAGMA journal_mode=WAL"); UUID id = first.create().getId(); first.setSupply(10);
            first.commands.requestTarget(id, "laborer", 8);
            var barrier = new CyclicBarrier(2);
            var hire = pool.submit(() -> { barrier.await(); first.applyPendingForPersistenceTest(id); return null; });
            var reduce = pool.submit(() -> { barrier.await(); try { second.setSupply(5); return true; } catch (IllegalStateException expected) { return false; } });
            hire.get(10, TimeUnit.SECONDS); boolean reduced = reduce.get(10, TimeUnit.SECONDS);
            assertEquals(reduced ? 5 : 8, first.supply.occupied(TestIdentities.REGION, "laborer"));
            assertEquals(reduced ? 5 : 10, second.supply.find(TestIdentities.REGION, "laborer").orElseThrow().totalSupply());
        }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void unknownCreationNeverPublishesOrRetriesAndExplicitFreshVerificationResolvesIt(boolean actuallyCommitted) throws Exception {
        var reference = new AtomicReference<Faults>();
        try (var w = world(reference)) {
            var faults = reference.get(); faults.commits = 0;
            faults.failCommitBefore = !actuallyCommitted; faults.failCommitAfter = actuallyCommitted;
            var uncertain = assertThrows(BuildingCreationUnconfirmed.class, w::create);
            assertEquals(1, faults.commits); assertTrue(w.registry.getAll().isEmpty());
            assertInstanceOf(CommitOutcomeUnknownException.class, uncertain.getCause());
            var verified = w.buildings.verifyCreation(uncertain.candidate());
            assertEquals(actuallyCommitted, verified.isPresent());
            assertEquals(actuallyCommitted ? 1 : 0, w.registry.getAll().size());
            assertEquals(1, faults.commits, "Verification must not run a second creation or use the failed connection");
            try (var fresh = SqliteEconomyDatabase.open(file()); var query = fresh.createStatement()) {
                for (String table : List.of("economic_buildings", "production_method_configurations", "production_executions", "building_staffing")) {
                    try (var rows = query.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(rows.next()); assertEquals(actuallyCommitted ? 1 : 0, rows.getLong(1)); }
                }
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void unknownPendingCommitReturnsNoSuccessAndRestartReadsTheActualRevision(boolean actuallyCommitted) throws Exception {
        var reference = new AtomicReference<Faults>(); UUID id;
        try (var w = world(reference)) {
            id = w.create().getId(); var faults = reference.get(); faults.commits = 0;
            faults.failCommitBefore = !actuallyCommitted; faults.failCommitAfter = actuallyCommitted;
            assertThrows(CommitOutcomeUnknownException.class, () -> w.commands.requestTarget(id, "laborer", 3));
            assertEquals(1, faults.commits);
        }
        try (var restored = new LaborTestWorld(file())) {
            var root = restored.staffing.load(id);
            assertEquals(actuallyCommitted ? 1 : 0, root.revision());
            assertEquals(actuallyCommitted ? Map.of("laborer", 3) : Map.of(), root.pending());
            assertTrue(root.employed().isEmpty());
        }
    }
    @Test void cleanupFailureAfterCreationAlsoRequiresFreshVerificationBeforePublishing() throws Exception {
        var reference = new AtomicReference<Faults>();
        try (var w = world(reference)) {
            reference.get().failReset = true;
            var unknown = assertThrows(BuildingCreationUnconfirmed.class, w::create);
            assertTrue(w.registry.getAll().isEmpty());
            assertThrows(CommitOutcomeUnknownException.class, () -> w.identities.loadAll());
            assertTrue(w.buildings.verifyCreation(unknown.candidate()).isPresent());
        }
    }
    @Test void savepointAcknowledgementFailureCannotPublishAnyOfTheFourRoots() throws Exception {
        var reference = new AtomicReference<Faults>();
        try (var w = world(reference)) {
            reference.get().failRelease = true;
            var unknown = assertThrows(BuildingCreationUnconfirmed.class, w::create);
            assertTrue(w.registry.getAll().isEmpty());
            assertTrue(w.buildings.verifyCreation(unknown.candidate()).isEmpty());
        }
    }
    @Test void rollbackAndCloseFailureKeepUncommittedStaffingAndCachedHandlesUntrusted() throws Exception {
        var reference = new AtomicReference<Faults>();
        try (var w = world(reference); var observer = new LaborTestWorld(file())) {
            UUID id = w.create().getId();
            w.setSupply(10); w.commands.requestTarget(id, "laborer", 3); w.applyPendingForPersistenceTest(id);
            w.commands.requestTarget(id, "laborer", 5);
            w.sql("CREATE TRIGGER reject_roles BEFORE INSERT ON building_staffing_roles BEGIN SELECT RAISE(ABORT,'role write failed'); END");
            var guarded = SqliteConnectionGuard.protect(w.connection);
            try (var cached = guarded.createStatement()) {
                var faults = reference.get(); faults.failRollback = true; faults.failClose = true; faults.commits = 0; faults.resets = 0;
                assertThrows(CommitOutcomeUnknownException.class, () -> w.applyPendingForPersistenceTest(id));
                assertEquals(0, faults.commits); assertEquals(0, faults.resets);
                assertEquals(3, observer.staffing.load(id).revision());
                assertEquals(Map.of("laborer", 3), observer.staffing.load(id).employed());
                assertEquals(3, observer.supply.occupied(TestIdentities.REGION, "laborer"));
                assertThrows(SQLException.class, () -> cached.executeQuery("SELECT 1"));
                assertThrows(CommitOutcomeUnknownException.class, () -> w.staffing.load(id));
            }
        }
    }
    @Test void queryUsesOneDeferredSnapshotWithoutHoldingAnImmediateWriterLock() throws Exception {
        var reference = new AtomicReference<Faults>();
        try (var w = world(reference); var other = new LaborTestWorld(file())) {
            w.sql("PRAGMA journal_mode=WAL"); UUID id = w.create().getId(); w.setSupply(10);
            var mutated = new AtomicBoolean();
            reference.get().beforeSql = text -> {
                if (text.startsWith("SELECT revision FROM building_staffing") && mutated.compareAndSet(false, true)) other.setSupply(9);
            };
            var snapshot = new StaffingCalculator(new SqliteStaffingQuery(w.connection, w.recipes, w.occupations, w.lock)).calculate(id);
            assertTrue(mutated.get());
            assertEquals(10, snapshot.occupations().get("laborer").totalSupply().orElseThrow());
            assertEquals(9, other.supply.find(TestIdentities.REGION, "laborer").orElseThrow().totalSupply());
        }
    }
    @Test void committedLoadsAndExpiredSessionsCannotReadAnUnresolvedTransaction() throws Exception {
        try (var w = new LaborTestWorld(file())) {
            UUID id = w.create().getId(); var captured = new AtomicReference<SqliteTransactions.ReadSession>();
            SqliteTransactions.read(w.connection, tx -> { captured.set(tx); return w.staffing.inSnapshot(tx, id); });
            assertThrows(IllegalStateException.class, () -> w.staffing.inSnapshot(captured.get(), id));
            w.connection.setAutoCommit(false);
            try {
                assertThrows(CommitOutcomeUnknownException.class, () -> w.staffing.load(id));
                assertThrows(CommitOutcomeUnknownException.class, () -> w.identities.load(id));
                assertThrows(CommitOutcomeUnknownException.class, () -> w.supply.occupied(TestIdentities.REGION, "laborer"));
                assertThrows(CommitOutcomeUnknownException.class, () -> w.commands.requestTarget(id, "laborer", 1));
            } finally { w.connection.rollback(); w.connection.setAutoCommit(true); }
        }
    }
    private LaborTestWorld world(AtomicReference<Faults> reference) throws Exception {
        return new LaborTestWorld(file(), base -> { var faults = new Faults(base); reference.set(faults); return faults.connection; });
    }
    private static void sql(Connection connection, String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    private static final class Faults implements InvocationHandler {
        final Connection delegate, connection;
        boolean failCommitBefore, failCommitAfter, failRollback, failClose, failReset, failRelease;
        int commits, resets;
        java.util.function.Consumer<String> beforeSql = sql -> {};
        Faults(Connection delegate) {
            this.delegate = delegate;
            connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, this);
        }
        @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "commit" -> { commits++; if (failCommitBefore) throw new SQLException("injected before commit"); }
                case "rollback" -> { if (failRollback) throw new SQLException("injected rollback failure"); }
                case "close" -> { if (failClose) throw new SQLException("injected close failure"); }
                case "releaseSavepoint" -> { if (failRelease) throw new SQLException("injected release failure"); }
                case "setAutoCommit" -> { if (Boolean.TRUE.equals(arguments[0])) { resets++; if (failReset) throw new SQLException("injected reset failure"); } }
                case "prepareStatement" -> beforeSql.accept((String) arguments[0]);
            }
            Object result;
            try { result = method.invoke(delegate, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
            if (name.equals("commit") && failCommitAfter) throw new SQLException("injected after durable commit");
            return result;
        }
    }
}
