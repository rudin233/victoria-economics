package com.bbmurloc.victoriaeconomics.server.inventory.infrastructure;

import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionSettlement;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bbmurloc.victoriaeconomics.server.production.domain.ProductionSettlement.Outcome.*;

@Timeout(30)
class SqliteInventoryConcurrencyTest {
    @TempDir Path directory;
    private Path file;
    private Connection physical, connection, observer;
    private SqliteInventoryStore store, other;
    private final UUID location = UUID.randomUUID(), batch = UUID.randomUUID();

    @BeforeEach void open() throws Exception {
        file = directory.resolve("stock.sqlite");
        physical = DriverManager.getConnection("jdbc:sqlite:" + file);
        connection = SqliteConnectionGuard.protect(physical);
        EconomySchema.initialize(connection);
        observer = SqliteConnectionGuard.protect(DriverManager.getConnection("jdbc:sqlite:" + file));
        sql(observer, "PRAGMA busy_timeout=5000");
        store = new SqliteInventoryStore(connection, new Object());
        other = new SqliteInventoryStore(observer, new Object());
        store.createLocation(location, 100);
        store.deposit(location, Map.of("ore", 10L));
    }
    @AfterEach void close() throws Exception { observer.close(); physical.close(); }

    private ProductionSettlement command(ProductionSettlement.Outcome outcome) {
        return new ProductionSettlement(batch, outcome, Map.of("ore", 7L), Map.of("metal", 5L));
    }

    @ParameterizedTest @ValueSource(strings = {"DELETE", "WAL"})
    void writePermissionPrecedesTheAuthoritativeInventoryRead(String mode) throws Exception {
        sql(connection, "PRAGMA journal_mode=" + mode);
        sql(observer, "PRAGMA busy_timeout=0");
        var faults = new Faults(connection);
        faults.beforeRead = () -> {
            var busy = assertThrows(SQLException.class, () -> sql(observer, "UPDATE inventory_locations SET revision=revision"));
            assertEquals(5, busy.getErrorCode());
        };
        new SqliteInventoryStore(faults.connection, new Object()).deposit(location, Map.of("ore", 1L));
        assertEquals(1, faults.reads);
        assertEquals(11L, other.inspect(location).quantities().get("ore"));
        assertEquals(2, revision());
    }

    @Test void twoWritersCannotReserveTheSameAvailableGoods() throws Exception {
        sql(connection, "PRAGMA journal_mode=WAL");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Future<Boolean> first = pool.submit(() -> reserveAtBarrier(store, UUID.randomUUID(), barrier));
            Future<Boolean> second = pool.submit(() -> reserveAtBarrier(other, UUID.randomUUID(), barrier));
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, store.inspect(location).reservations().size());
        assertEquals(3, new com.bbmurloc.victoriaeconomics.server.inventory.domain.GoodsInventory(store.inspect(location)).available("ore"));
    }

    private boolean reserveAtBarrier(SqliteInventoryStore candidate, UUID id, CyclicBarrier barrier) throws Exception {
        barrier.await();
        try { candidate.reserve(location, id, Map.of("ore", 7L)); return true; }
        catch (IllegalStateException expected) { return false; }
    }

    @Test void twoWritersCannotLoseConcurrentDeposits() throws Exception {
        sql(connection, "PRAGMA journal_mode=WAL");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var first = pool.submit(() -> { barrier.await(); store.deposit(location, Map.of("ore", 2L)); return null; });
            var second = pool.submit(() -> { barrier.await(); other.deposit(location, Map.of("ore", 3L)); return null; });
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(15L, store.inspect(location).quantities().get("ore"));
        assertEquals(3, revision());
    }

    @Test void competingCompletionAndAbortCommitExactlyOneOutcome() throws Exception {
        sql(connection, "PRAGMA journal_mode=WAL");
        store.reserve(location, batch, command(COMPLETED).inputs());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var first = pool.submit(() -> settleAtBarrier(store, command(COMPLETED), barrier));
            var second = pool.submit(() -> settleAtBarrier(other, command(ABORTED), barrier));
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        var state = store.inspect(location);
        assertEquals(1, state.settlements().size());
        assertTrue(state.reservations().isEmpty());
        boolean completed = state.settlements().get(batch).command().outcome() == COMPLETED;
        assertEquals(completed ? 3L : 10L, state.quantities().get("ore"));
        assertEquals(completed ? 5L : 0L, state.quantities().getOrDefault("metal", 0L));
    }

    private boolean settleAtBarrier(SqliteInventoryStore candidate, ProductionSettlement command, CyclicBarrier barrier) throws Exception {
        barrier.await();
        try { candidate.settle(location, command); return true; }
        catch (IllegalStateException expected) { return false; }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void outcomeAndCompleteRecipeMustMatchEveryReplayIncludingAborts(boolean completeFirst) {
        var first = command(completeFirst ? COMPLETED : ABORTED);
        store.reserve(location, batch, first.inputs());
        var receipt = store.settle(location, first);
        long before = revision();
        assertEquals(receipt, other.settle(location, first));
        assertEquals(before, revision(), "A matching replay does not even advance inventory revision");
        var snapshot = store.inspect(location);
        assertThrows(IllegalStateException.class, () -> other.settle(location, command(completeFirst ? ABORTED : COMPLETED)));
        assertThrows(IllegalStateException.class, () -> other.settle(location,
                new ProductionSettlement(batch, first.outcome(), first.inputs(), Map.of("metal", 6L))));
        assertThrows(IllegalStateException.class, () -> other.settle(location,
                new ProductionSettlement(batch, first.outcome(), Map.of("ore", 6L), first.outputs())));
        assertEquals(snapshot, store.inspect(location));
        assertEquals(before, revision());
    }

    @Test void casMismatchRollsBackWithoutPublishingTheDetachedMutation() {
        var faults = new Faults(connection);
        faults.beforeWrite = () -> {
            try { sql(connection, "UPDATE inventory_locations SET revision=revision+1"); }
            catch (SQLException failure) { throw new AssertionError(failure); }
        };
        assertThrows(IllegalStateException.class, () -> new SqliteInventoryStore(faults.connection, new Object()).deposit(location, Map.of("ore", 1L)));
        assertEquals(1, revision());
        assertEquals(10L, other.inspect(location).quantities().get("ore"));
        assertEquals(0, faults.commits);
        assertEquals(1, faults.rollbacks);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void unknownCommitDoesNotRetryAndRestartVerifiesTheExactSettlement(boolean actuallyCommitted) throws Exception {
        store.reserve(location, batch, command(COMPLETED).inputs());
        var faults = new Faults(connection);
        faults.failCommitAfter = actuallyCommitted;
        faults.failCommitBefore = !actuallyCommitted;
        assertThrows(CommitOutcomeUnknownException.class, () -> new SqliteInventoryStore(faults.connection, new Object()).settle(location, command(COMPLETED)));
        assertEquals(1, faults.commits);
        assertEquals(actuallyCommitted ? 3L : 10L, other.inspect(location).quantities().get("ore"));
        try (var fresh = SqliteEconomyDatabase.open(file)) {
            var restored = new SqliteInventoryStore(fresh, new Object());
            assertEquals(command(COMPLETED), restored.settle(location, command(COMPLETED)).command());
            assertEquals(3L, restored.inspect(location).quantities().get("ore"));
            assertEquals(5L, restored.inspect(location).quantities().get("metal"));
            assertEquals(1, restored.inspect(location).settlements().size());
        }
    }

    @Test void cleanupFailureDoesNotReturnAReceiptAndQuarantinesTheConnection() {
        store.reserve(location, batch, command(ABORTED).inputs());
        var faults = new Faults(connection);
        faults.failReset = true;
        assertThrows(CommitOutcomeUnknownException.class, () -> new SqliteInventoryStore(faults.connection, new Object()).settle(location, command(ABORTED)));
        assertEquals(1, faults.commits);
        assertEquals(ABORTED, other.inspect(location).settlements().get(batch).command().outcome());
        assertEquals(10L, other.inspect(location).quantities().get("ore"));
        assertThrows(CommitOutcomeUnknownException.class, () -> store.inspect(location));
    }

    @Test void failedRollbackAndCloseCannotCommitUnacknowledgedInventoryWorkOrReuseHandles() throws Exception {
        var faults = new Faults(connection);
        faults.failRollback = true; faults.failClose = true;
        faults.beforeWrite = () -> {
            try { sql(connection, "UPDATE inventory_locations SET revision=revision+1"); }
            catch (SQLException failure) { throw new AssertionError(failure); }
            throw new IllegalStateException("injected failure after a real local write");
        };
        var guarded = SqliteConnectionGuard.protect(faults.connection);
        try (var cached = guarded.createStatement()) {
            assertThrows(CommitOutcomeUnknownException.class, () -> new SqliteInventoryStore(guarded, new Object()).deposit(location, Map.of("ore", 1L)));
            assertEquals(0, faults.commits); assertEquals(0, faults.resets);
            assertEquals(1, revision());
            assertEquals(10L, other.inspect(location).quantities().get("ore"));
            assertThrows(SQLException.class, () -> cached.executeQuery("SELECT 1"));
            assertThrows(CommitOutcomeUnknownException.class, () -> store.deposit(location, Map.of("ore", 1L)));
        }
    }

    @Test void revisionOverflowAndInvalidAmountsLeaveCommittedStateUntouched() throws Exception {
        sql(connection, "UPDATE inventory_locations SET revision=9223372036854775807");
        var before = store.inspect(location);
        assertThrows(ArithmeticException.class, () -> store.deposit(location, Map.of("ore", 1L)));
        assertThrows(IllegalArgumentException.class, () -> store.deposit(location, Map.of("ore", 0L)));
        assertThrows(IllegalArgumentException.class, () -> store.withdraw(location, Map.of("ore", -1L)));
        assertEquals(before, other.inspect(location));
        assertEquals(Long.MAX_VALUE, revision());
    }

    @Test void committedInventoryReadsAndWritesRejectAnUnresolvedLocalTransaction() throws Exception {
        connection.setAutoCommit(false);
        try {
            assertThrows(CommitOutcomeUnknownException.class, () -> store.inspect(location));
            assertThrows(CommitOutcomeUnknownException.class, () -> store.deposit(location, Map.of("ore", 1L)));
        } finally { connection.rollback(); connection.setAutoCommit(true); }
        assertEquals(10L, other.inspect(location).quantities().get("ore"));
    }

    private long revision() {
        try (var query = observer.createStatement(); var rows = query.executeQuery("SELECT revision FROM inventory_locations")) {
            assertTrue(rows.next()); return rows.getLong(1);
        } catch (SQLException failure) { throw new AssertionError(failure); }
    }
    private static void sql(Connection connection, String text) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(text); }
    }

    private static final class Faults implements InvocationHandler {
        final Connection delegate, connection;
        boolean failCommitBefore, failCommitAfter, failReset, failRollback, failClose;
        Runnable beforeRead, beforeWrite;
        int reads, commits, rollbacks, resets;
        Faults(Connection delegate) {
            this.delegate = delegate;
            connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, this);
        }
        @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "commit" -> { commits++; if (failCommitBefore) throw new SQLException("commit failed before driver"); }
                case "rollback" -> { rollbacks++; if (failRollback) throw new SQLException("rollback failed"); }
                case "close" -> { if (failClose) throw new SQLException("close failed"); }
                case "setAutoCommit" -> {
                    if (Boolean.TRUE.equals(arguments[0])) { resets++; if (failReset) throw new SQLException("reset failed"); }
                }
                case "prepareStatement" -> {
                    String sql = (String) arguments[0];
                    if (sql.startsWith("SELECT state_json")) { reads++; if (beforeRead != null) beforeRead.run(); }
                    if (sql.startsWith("UPDATE inventory_locations") && beforeWrite != null) beforeWrite.run();
                }
            }
            Object result;
            try { result = method.invoke(delegate, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
            if (name.equals("commit") && failCommitAfter) throw new SQLException("receipt lost after actual commit");
            return result;
        }
    }
}
