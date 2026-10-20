package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.common.definition.building.*;
import com.bbmurloc.victoriaeconomics.common.definition.production.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.storage.EconomyUnitOfWork;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteConfig.TransactionMode;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class EconomyUnitOfWorkTest {
    @TempDir Path directory;
    private Path file;
    private Connection physical, connection, observer;
    private ProductionRecipeResolver recipes;
    private SqliteProductionMethodConfigurationRepository methods;
    private SqliteProductionExecutionRepository executions;
    private SqliteEconomyUnitOfWork uow;
    private final Object lock = new Object();
    private UUID id;

    @BeforeEach void open() throws Exception {
        file = directory.resolve("economy.sqlite");
        physical = DriverManager.getConnection("jdbc:sqlite:" + file);
        connection = SqliteConnectionGuard.protect(physical);
        EconomySchema.initialize(connection);
        observer = DriverManager.getConnection("jdbc:sqlite:" + file);
        sql(observer, "PRAGMA busy_timeout=0");
        sql(connection, "PRAGMA busy_timeout=0");
        var types = new BuildingTypeRegistry();
        var groups = new ProductionMethodGroupRegistry();
        var definitions = new ProductionMethodRegistry();
        types.register(new BuildingTypeDefinition("factory", "industry", "equipment", 100, List.of("base")));
        groups.register(new ProductionMethodGroupDefinition("base", "factory", List.of("a", "b")));
        definitions.register(new ProductionMethodDefinition("a", "base", 1, Map.of("ore", 10L), Map.of("metal", 10L), Map.of("worker", 10)));
        definitions.register(new ProductionMethodDefinition("b", "base", 2, Map.of("ore", 10L), Map.of("metal", 20L), Map.of("worker", 10)));
        recipes = new ProductionRecipeResolver(types, groups, definitions);
        methods = new SqliteProductionMethodConfigurationRepository(connection, recipes, lock);
        executions = new SqliteProductionExecutionRepository(connection, lock);
        uow = unitOfWork(connection);
        id = UUID.randomUUID();
        try (var statement = connection.prepareStatement("INSERT INTO economic_buildings VALUES(?,'factory','ACTIVE')")) {
            statement.setString(1, id.toString());
            statement.executeUpdate();
        }
        methods.create(new ProductionMethodConfiguration(id, recipes.rulesFor("factory"), Map.of("base", "a"), null));
        executions.create(new ProductionExecution(id));
    }

    @AfterEach void close() throws Exception {
        observer.close();
        physical.close(); // Test-only escape for deliberately failed physical closes.
    }

    @Test void loadsBothDetachedRootsAndCommitsWithOnePhysicalCommit() {
        var faults = new Faults(connection);
        var pm = new AtomicReference<ProductionMethodConfiguration>();
        var execution = new AtomicReference<ProductionExecution>();
        unitOfWork(faults.connection).write(session -> {
            pm.set(session.methods().load(id));
            execution.set(session.executions().load(id));
            assertSame(pm.get(), session.methods().load(id));
            assertSame(execution.get(), session.executions().load(id));
            pm.get().requestSelection("base", "b", false);
            execution.get().idleMethodsReconciled(pm.get().effectiveRevision());
            execution.get().requestAutomatic(true);
            session.methods().save(pm.get(), 0);
            session.executions().save(execution.get(), 0);
            assertEquals(0, execution.get().executionRevision(), "Staging is not a commit acknowledgement");
            assertCommittedPair(0, "a", false);
        });
        assertEquals(1, faults.commits);
        assertEquals(0, faults.savepoints);
        assertEquals(1, execution.get().executionRevision());
        assertEquals(1, pm.get().configurationRevision());
        assertCommittedPair(1, "b", true);
        assertEquals(1, executions.load(id).methodsEffectiveRevision());
        assertNotSame(pm.get(), methods.load(id));
        assertNotSame(execution.get(), executions.load(id));
    }

    @Test void observerSeesTheOldCompletePairAfterTheFirstPhysicalWrite() {
        var faults = new Faults(connection);
        var inspected = new AtomicBoolean();
        faults.beforeExecutionUpdate = () -> {
            inspected.set(true);
            assertCommittedPair(0, "a", false);
            // PM UPDATE has executed on the writer, but remains invisible to the observer.
            assertEquals(1, scalar(connection, "SELECT configuration_revision FROM production_method_configurations"));
        };
        unitOfWork(faults.connection).write(this::changeBoth);
        assertTrue(inspected.get());
        assertCommittedPair(1, "b", true);
    }

    @Test void pmExecutionAndNewBatchCommitInOneTransaction() {
        UUID batchId = UUID.randomUUID();
        uow.write(session -> {
            var pm = session.methods().load(id);
            var execution = session.executions().load(id);
            pm.requestSelection("base", "b", false);
            execution.start(batchId, commitment(pm));
            session.methods().save(pm, 0);
            session.executions().save(execution, 0);
        });
        assertEquals(1, methods.load(id).configurationRevision());
        var execution = executions.load(id);
        assertEquals(1, execution.executionRevision());
        assertEquals(batchId, execution.batch().getId());
        assertEquals("b", execution.batch().getConfiguration().productionMethodSelections().get("base"));
        assertEquals(1, scalar(observer, "SELECT COUNT(*) FROM production_batches"));
    }

    @Test void failedBatchInsertRollsBackPmExecutionAndBatchTogether() throws Exception {
        sql(connection, "CREATE TRIGGER reject_batch BEFORE INSERT ON production_batches BEGIN SELECT RAISE(ABORT,'batch failed'); END");
        var execution = new AtomicReference<ProductionExecution>();
        assertThrows(IllegalStateException.class, () -> uow.write(session -> {
            var pm = session.methods().load(id);
            execution.set(session.executions().load(id));
            pm.requestSelection("base", "b", false);
            execution.get().start(UUID.randomUUID(), commitment(pm));
            session.methods().save(pm, 0);
            session.executions().save(execution.get(), 0);
        }));
        assertNull(execution.get().batch());
        assertEquals(0, execution.get().executionRevision());
        assertCommittedPair(0, "a", false);
        assertEquals(0, scalar(observer, "SELECT COUNT(*) FROM production_batches"));
    }

    @Test void driverDoesNotReacquireImmediateLockAfterTheActualCommit() {
        var faults = new Faults(connection);
        var execution = new AtomicReference<ProductionExecution>();
        faults.afterCommit = () -> {
            assertEquals(0, execution.get().executionRevision(), "Physical commit alone is not yet a returned UoW receipt");
            try { sql(observer, "UPDATE economic_buildings SET status=status"); }
            catch (SQLException failure) { throw new AssertionError("Driver restarted a write transaction during cleanup", failure); }
        };
        unitOfWork(faults.connection).write(session -> {
            execution.set(session.executions().load(id));
            changeBoth(session);
        });
        assertEquals(1, execution.get().executionRevision());
        assertCommittedPair(1, "b", true);
    }

    @Test void secondAggregateSqlFailureRollsBackTheFirstAndRewindsWorkingRevisions() throws Exception {
        sql(connection, "CREATE TRIGGER reject_execution BEFORE UPDATE ON production_executions BEGIN SELECT RAISE(ABORT,'second write failed'); END");
        var pm = new AtomicReference<ProductionMethodConfiguration>();
        var execution = new AtomicReference<ProductionExecution>();
        assertThrows(IllegalStateException.class, () -> uow.write(session -> {
            pm.set(session.methods().load(id));
            execution.set(session.executions().load(id));
            changeBoth(session);
        }));
        assertEquals(0, pm.get().configurationRevision());
        assertEquals(0, pm.get().effectiveRevision());
        assertEquals("a", pm.get().effective().methods().get("base"));
        assertEquals(0, execution.get().executionRevision());
        assertFalse(execution.get().automatic());
        assertCommittedPair(0, "a", false);
        SqliteTransactions.requireCommittedReads(connection);
    }

    @Test void businessExceptionDiscardsSavedAndUnsavedMutationsAndTheSessionCloses() {
        var sessionRef = new AtomicReference<EconomyUnitOfWork.Session>();
        var pm = new AtomicReference<ProductionMethodConfiguration>();
        var execution = new AtomicReference<ProductionExecution>();
        var failure = new IllegalArgumentException("business failed");
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> uow.write(session -> {
            sessionRef.set(session);
            pm.set(session.methods().load(id));
            execution.set(session.executions().load(id));
            pm.get().requestSelection("base", "b", true);
            execution.get().requestAutomatic(true);
            session.executions().save(execution.get(), 0);
            throw failure;
        })));
        assertEquals(0, pm.get().configurationRevision());
        assertTrue(pm.get().pending().isEmpty());
        assertEquals(0, execution.get().executionRevision());
        assertFalse(execution.get().automatic());
        assertThrows(IllegalStateException.class, () -> sessionRef.get().methods());
        assertCommittedPair(0, "a", false);
    }

    @Test void errorFromApplicationWorkRollsBackBothRoots() {
        assertThrows(AssertionError.class, () -> uow.write(session -> {
            changeBoth(session);
            throw new AssertionError("application error");
        }));
        assertCommittedPair(0, "a", false);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unknownCommitNeverRetriesOrPublishesSessionRevisions(boolean commitActuallySucceeded) {
        var faults = new Faults(connection);
        if (commitActuallySucceeded) faults.commitAfterFailure = new SQLException("lost commit receipt");
        else faults.commitBeforeFailure = new SQLException("commit failed before driver");
        var calls = new AtomicInteger();
        var pm = new AtomicReference<ProductionMethodConfiguration>();
        var execution = new AtomicReference<ProductionExecution>();
        assertThrows(CommitOutcomeUnknownException.class, () -> unitOfWork(faults.connection).write(session -> {
            calls.incrementAndGet();
            pm.set(session.methods().load(id));
            execution.set(session.executions().load(id));
            changeBoth(session);
        }));
        assertEquals(1, calls.get());
        assertEquals(1, faults.commits);
        assertEquals(1, faults.rollbacks);
        assertEquals(0, pm.get().configurationRevision(), "Rewinding a local copy does not assert database rollback");
        assertEquals(0, execution.get().executionRevision());
        assertCommittedPair(commitActuallySucceeded ? 1 : 0, commitActuallySucceeded ? "b" : "a", commitActuallySucceeded);
        // Recover using independently loaded committed facts, never the failed callback's objects.
        assertEquals(commitActuallySucceeded ? 1 : 0, methods.load(id).configurationRevision());
        assertEquals(commitActuallySucceeded ? 1 : 0, executions.load(id).executionRevision());
    }

    @Test void cleanupFailureAfterActualCommitDoesNotPublishAndQuarantinesTheConnection() {
        var faults = new Faults(connection);
        faults.resetFailure = new SQLException("JDBC reset failed");
        var execution = new AtomicReference<ProductionExecution>();
        assertThrows(CommitOutcomeUnknownException.class, () -> unitOfWork(faults.connection).write(session -> {
            execution.set(session.executions().load(id));
            changeBoth(session);
        }));
        assertEquals(1, faults.commits);
        assertEquals(0, execution.get().executionRevision());
        assertCommittedPair(1, "b", true);
        assertThrows(CommitOutcomeUnknownException.class, () -> executions.load(id));
    }

    @Test void rollbackAndCloseFailureRetainQuarantineForTheUowAndDerivedJdbcHandles() throws Exception {
        sql(connection, "CREATE TRIGGER reject_execution BEFORE UPDATE ON production_executions BEGIN SELECT RAISE(ABORT,'second write failed'); END");
        var faults = new Faults(connection);
        faults.rollbackFailure = new SQLException("rollback failed");
        faults.closeFailure = new SQLException("close failed");
        var guarded = SqliteConnectionGuard.protect(faults.connection);
        try (var cached = guarded.createStatement()) {
            assertThrows(CommitOutcomeUnknownException.class, () -> unitOfWork(guarded).write(this::changeBoth));
            assertEquals(0, faults.commits);
            assertEquals(0, faults.resets);
            assertThrows(SQLException.class, () -> cached.executeQuery("SELECT 1"));
            assertThrows(CommitOutcomeUnknownException.class, () -> unitOfWork(guarded).write(session -> fail("quarantined")));
            assertThrows(CommitOutcomeUnknownException.class, () -> executions.load(id));
            assertCommittedPair(0, "a", false);
        }
    }

    @Test void caughtUnsafeSavepointFailureCannotCommitTheOuterUow() {
        var faults = new Faults(connection);
        faults.releaseFailure = new SQLException("savepoint release failed");
        faults.savepointRollbackFailure = new SQLException("savepoint rollback failed");
        assertThrows(CommitOutcomeUnknownException.class, () -> unitOfWork(faults.connection).write(session -> {
            changeBoth(session);
            assertThrows(CommitOutcomeUnknownException.class, () -> SqliteTransactions.run(faults.connection, () -> {
                sql(faults.connection, "UPDATE production_executions SET automatic=1");
                return null;
            }));
        }));
        assertEquals(0, faults.commits);
        assertEquals(0, faults.resets);
        assertCommittedPair(0, "a", false);
    }

    @Test void defaultDeferredBeginDoesNotAcquireWritePermission() throws Exception {
        assertEquals("3.46.1.0", connection.getMetaData().getDriverVersion());
        assertEquals(TransactionMode.DEFERRED, ((SQLiteConnection) physical).getConnectionConfig().getTransactionMode());
        connection.setAutoCommit(false);
        try {
            sql(observer, "UPDATE production_executions SET automatic=1");
            assertEquals(1, scalar(observer, "SELECT automatic FROM production_executions"));
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    @Test void writeModeIsScopedAndOriginalDriverConfigurationIsRestoredEvenOnFailure() throws Exception {
        var config = ((SQLiteConnection) physical).getConnectionConfig();
        config.setTransactionMode(TransactionMode.EXCLUSIVE);
        var failure = new IllegalStateException("business failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> uow.write(session -> {
            assertEquals(TransactionMode.DEFERRED, config.getTransactionMode(), "Only the BEGIN uses IMMEDIATE");
            assertEquals(5, assertThrows(SQLException.class, () -> sql(observer, "UPDATE economic_buildings SET status=status")).getErrorCode());
            throw failure;
        })));
        assertEquals(TransactionMode.EXCLUSIVE, config.getTransactionMode());
        assertTrue(connection.getAutoCommit());
        sql(observer, "UPDATE economic_buildings SET status=status");
    }

    @ParameterizedTest @ValueSource(strings = {"DELETE", "WAL"})
    void uowAcquiresWritePermissionBeforeTheFirstBusinessRead(String journalMode) throws Exception {
        sql(connection, "PRAGMA journal_mode=" + journalMode);
        uow.write(session -> {
            // First business action: another physical connection cannot write, before any root load.
            var busy = assertThrows(SQLException.class, () -> sql(observer, "UPDATE production_executions SET automatic=1"));
            assertEquals(5, busy.getErrorCode()); // SQLITE_BUSY
            assertEquals(0, session.methods().load(id).configurationRevision());
            assertEquals(0, session.executions().load(id).executionRevision());
            assertThrows(CommitOutcomeUnknownException.class, () -> methods.load(id));
            assertThrows(CommitOutcomeUnknownException.class, () -> executions.load(id));
            changeBoth(session);
        });
        assertCommittedPair(1, "b", true);
        // Write permission is released after commit/cleanup.
        sql(observer, "UPDATE production_executions SET automatic=0");
        assertEquals(TransactionMode.DEFERRED, ((SQLiteConnection) physical).getConnectionConfig().getTransactionMode());
    }

    @Test void competingWriterPreventsTheUowCallbackAndAllAuthorityReads() throws Exception {
        observer.setAutoCommit(false);
        sql(observer, "UPDATE production_executions SET automatic=1");
        var calls = new AtomicInteger();
        try {
            assertThrows(CommitOutcomeUnknownException.class, () -> uow.write(session -> {
                calls.incrementAndGet();
                session.methods().load(id);
                session.executions().load(id);
            }));
            assertEquals(0, calls.get(), "Failed BEGIN IMMEDIATE must never enter application work");
            assertTrue(connection.isClosed(), "Conservatively quarantine an unconfirmed JDBC begin");
            observer.commit();
        } finally {
            observer.rollback();
            observer.setAutoCommit(true);
        }
        try (var fresh = SqliteConnectionGuard.protect(DriverManager.getConnection("jdbc:sqlite:" + file))) {
            unitOfWork(fresh).write(session -> assertTrue(session.executions().load(id).automatic()));
        }
    }

    @Test void waitingUowReadsTheCompetingWritersLatestCommitAfterGettingTheLock() throws Exception {
        sql(connection, "PRAGMA journal_mode=WAL");
        observer.setAutoCommit(false);
        sql(observer, "UPDATE production_executions SET automatic=1,execution_revision=1");
        sql(observer, "UPDATE production_method_configurations SET configuration_revision=1");
        sql(connection, "PRAGMA busy_timeout=5000");
        var beginning = new CountDownLatch(1);
        var faults = new Faults(connection);
        faults.beforeBegin = beginning::countDown;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> unitOfWork(faults.connection).write(session -> {
                assertEquals(1, session.methods().load(id).configurationRevision());
                assertEquals(1, session.executions().load(id).executionRevision());
                assertTrue(session.executions().load(id).automatic());
            }));
            assertTrue(beginning.await(5, TimeUnit.SECONDS));
            observer.commit();
            observer.setAutoCommit(true);
            result.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, faults.commits);
    }

    @Test void ordinaryCommittedLoadsDoNotAcquireImmediateWriteLocks() throws Exception {
        observer.setAutoCommit(false);
        try {
            sql(observer, "UPDATE production_executions SET automatic=1");
            assertFalse(executions.load(id).automatic(), "A committed reader can coexist with another RESERVED writer");
            assertEquals(0, methods.load(id).configurationRevision());
        } finally {
            observer.rollback();
            observer.setAutoCommit(true);
        }
    }

    @Test void sessionAndRepositoryCannotBeReusedOutsideTheirScopeOrOnAnotherConnection() {
        var saved = new AtomicReference<ProductionExecutionRepository.Session>();
        uow.write(session -> saved.set(session.executions()));
        assertThrows(IllegalStateException.class, () -> saved.get().load(id));
        assertThrows(IllegalStateException.class, () -> saved.get().save(new ProductionExecution(id), 0));
        var other = new SqliteProductionExecutionRepository(observer, new Object());
        SqliteTransactions.write(connection, transaction -> {
            assertThrows(IllegalArgumentException.class, () -> other.inTransaction(transaction));
            return null;
        });
    }

    @Test void sessionCannotBeUsedFromAnotherThread() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            uow.write(session -> {
                try {
                    executor.submit(() -> assertThrows(IllegalStateException.class, () -> session.methods())).get(5, TimeUnit.SECONDS);
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
        }
    }

    @Test void caughtSessionFailureStillAbortsAllStagedWrites() {
        assertThrows(IllegalStateException.class, () -> uow.write(session -> {
            changeBoth(session);
            assertThrows(IllegalStateException.class, () -> session.methods().load(UUID.randomUUID()));
        }));
        assertCommittedPair(0, "a", false);
    }

    @Test void foreignRootCannotBeSavedEvenWhenItHasTheSameBuildingAndRevision() {
        var foreign = executions.load(id);
        foreign.requestAutomatic(true);
        assertThrows(IllegalArgumentException.class, () -> uow.write(session -> {
            session.executions().load(id);
            session.executions().save(foreign, 0);
        }));
        assertCommittedPair(0, "a", false);
    }

    @Test void duplicateSaveAbortsTheWholeUowRatherThanAcknowledgingASavepoint() {
        assertThrows(IllegalStateException.class, () -> uow.write(session -> {
            changeBoth(session);
            session.executions().save(session.executions().load(id), 0);
        }));
        assertCommittedPair(0, "a", false);
    }

    @Test void staleCasOnTheSecondRootRollsBackTheFirstRoot() {
        assertThrows(ProductionRevisionConflict.class, () -> uow.write(session -> {
            changeBoth(session);
            // SQL fault injection represents a revision no longer matching the loaded root.
            try { sql(connection, "UPDATE production_executions SET execution_revision=9"); }
            catch (SQLException failure) { throw new AssertionError(failure); }
        }));
        assertCommittedPair(0, "a", false);
    }

    @Test void unsavedEditsAreNotPublishedOnSuccessfulReturn() {
        var pm = new AtomicReference<ProductionMethodConfiguration>();
        var execution = new AtomicReference<ProductionExecution>();
        uow.write(session -> {
            pm.set(session.methods().load(id));
            execution.set(session.executions().load(id));
            pm.get().requestSelection("base", "b", true);
            execution.get().requestAutomatic(true);
        });
        assertEquals(0, pm.get().configurationRevision());
        assertTrue(pm.get().pending().isEmpty());
        assertFalse(execution.get().automatic());
        assertCommittedPair(0, "a", false);
    }

    @Test void nestedUowAndStandaloneSavesCannotJoinAnUnresolvedTransaction() {
        var pm = methods.load(id);
        pm.requestSelection("base", "b", true);
        var execution = executions.load(id);
        execution.requestAutomatic(true);
        SqliteTransactions.run(connection, () -> {
            assertThrows(CommitOutcomeUnknownException.class, () -> uow.write(session -> fail("nested UoW")));
            assertThrows(CommitOutcomeUnknownException.class, () -> methods.save(pm, 0));
            assertThrows(CommitOutcomeUnknownException.class, () -> executions.save(execution, 0));
            assertEquals(0, execution.executionRevision());
            return null;
        });
        assertCommittedPair(0, "a", false);
    }

    @Test void guardedUowNeverExposesAnUnguardedDriver() throws Exception {
        assertThrows(SQLFeatureNotSupportedException.class, () -> connection.unwrap(SQLiteConnection.class));
        uow.write(this::changeBoth);
        assertThrows(SQLFeatureNotSupportedException.class, () -> connection.unwrap(SQLiteConnection.class));
        assertSame(connection, connection.getMetaData().getConnection());
    }

    private SqliteEconomyUnitOfWork unitOfWork(Connection candidate) { return new SqliteEconomyUnitOfWork(candidate, recipes, lock); }

    private void changeBoth(EconomyUnitOfWork.Session session) {
        var pm = session.methods().load(id);
        var execution = session.executions().load(id);
        pm.requestSelection("base", "b", false);
        execution.requestAutomatic(true);
        session.methods().save(pm, 0);
        session.executions().save(execution, 0);
    }

    private ProductionBatchConfiguration commitment(ProductionMethodConfiguration pm) {
        var employees = new ArrayList<EmploymentFact>();
        for (int i = 0; i < 10; i++) employees.add(new EmploymentFact(new UUID(0, i + 1), id, "worker"));
        var recipe = pm.recipe();
        var plan = new WorkforcePlanningService().plan(id, recipe.requiredWorkers(), 100, 100, employees, (npc, role) -> true);
        return new ProductionBatchConfiguration(pm.effective().methods(), recipe, 100, 100, plan, pm.effectiveRevision());
    }

    private void assertCommittedPair(long revision, String method, boolean automatic) {
        var pm = new SqliteProductionMethodConfigurationRepository(observer, recipes, new Object()).load(id);
        var execution = new SqliteProductionExecutionRepository(observer, new Object()).load(id);
        assertEquals(revision, pm.configurationRevision());
        assertEquals(method, pm.effective().methods().get("base"));
        assertEquals(revision, execution.executionRevision());
        assertEquals(automatic, execution.automatic());
    }

    private static void sql(Connection candidate, String text) throws SQLException {
        try (var statement = candidate.createStatement()) { statement.execute(text); }
    }

    private static long scalar(Connection candidate, String text) {
        try (var statement = candidate.createStatement(); var rows = statement.executeQuery(text)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        } catch (SQLException failure) { throw new AssertionError(failure); }
    }

    /** Inject faults only at JDBC boundaries; every database effect uses real file SQLite. */
    private static final class Faults implements InvocationHandler {
        private final Connection delegate;
        final Connection connection;
        SQLException commitBeforeFailure, commitAfterFailure, rollbackFailure, closeFailure, resetFailure;
        SQLException releaseFailure, savepointRollbackFailure;
        Runnable beforeExecutionUpdate, beforeBegin, afterCommit;
        int commits, rollbacks, resets, savepoints;

        Faults(Connection delegate) {
            this.delegate = delegate;
            connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, this);
        }

        @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "commit" -> { commits++; if (commitBeforeFailure != null) throw commitBeforeFailure; }
                case "rollback" -> {
                    if (arguments == null || arguments.length == 0) { rollbacks++; if (rollbackFailure != null) throw rollbackFailure; }
                    else if (savepointRollbackFailure != null) throw savepointRollbackFailure;
                }
                case "setAutoCommit" -> {
                    if (Boolean.TRUE.equals(arguments[0])) { resets++; if (resetFailure != null) throw resetFailure; }
                    else if (beforeBegin != null) beforeBegin.run();
                }
                case "setSavepoint" -> savepoints++;
                case "releaseSavepoint" -> { if (releaseFailure != null) throw releaseFailure; }
                case "close" -> { if (closeFailure != null) throw closeFailure; }
                case "prepareStatement" -> {
                    if (beforeExecutionUpdate != null && ((String) arguments[0]).startsWith("UPDATE production_executions")) beforeExecutionUpdate.run();
                }
            }
            Object result;
            try { result = method.invoke(delegate, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
            if (name.equals("commit") && afterCommit != null) afterCommit.run();
            if (name.equals("commit") && commitAfterFailure != null) throw commitAfterFailure;
            return result;
        }
    }
}
