package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SqliteTransactionsTest {
    @TempDir Path directory;
    private Path file;
    private Connection actual;
    private Faults faults;

    @BeforeEach
    void open() throws Exception {
        file = directory.resolve("transactions.sqlite");
        actual = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (var statement = actual.createStatement()) {
            statement.executeUpdate("CREATE TABLE entries(value INTEGER NOT NULL)");
        }
        faults = new Faults(actual);
    }

    @AfterEach
    void closePhysicalConnection() throws Exception {
        // Test-only physical handle: release even a deliberately uncloseable wrapper's resources.
        actual.close();
    }

    @Test
    void normalCommitPersistsDataAndReturnsTheResult() throws Exception {
        int result = SqliteTransactions.run(faults.connection, () -> {
            insert(faults.connection, 7);
            return 42;
        });

        assertEquals(42, result);
        assertEquals(List.of(7), committedValues());
        assertEquals(1, faults.commits);
        assertEquals(1, faults.resets);
        assertTrue(actual.getAutoCommit());
        SqliteTransactions.requireCommittedReads(faults.connection);
    }

    @Test
    void runtimeWorkFailureRollsBackAndLeavesTheConnectionUsable() throws Exception {
        var workFailure = new IllegalArgumentException("original work failure");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> SqliteTransactions.run(faults.connection, () -> {
                    insert(faults.connection, 7);
                    throw workFailure;
                }));

        assertSame(workFailure, failure);
        assertEquals(List.of(), committedValues());
        assertEquals(1, faults.rollbacks);
        assertEquals(0, faults.commits);
        assertTrue(actual.getAutoCommit());
        SqliteTransactions.run(faults.connection, () -> {
            insert(faults.connection, 8);
            return null;
        });
        assertEquals(List.of(8), committedValues());
    }

    @Test
    void checkedWorkFailureIsPreservedAfterSuccessfulRollback() throws Exception {
        var workFailure = new SQLException("original SQL work failure");
        var failure = assertThrows(IllegalStateException.class,
                () -> SqliteTransactions.run(faults.connection, () -> {
                    insert(faults.connection, 7);
                    throw workFailure;
                }));

        assertFalse(failure instanceof CommitOutcomeUnknownException);
        assertSame(workFailure, failure.getCause());
        assertEquals(List.of(), committedValues());
        assertTrue(actual.getAutoCommit());
    }

    @Test
    void errorFromWorkAlsoRollsBackBeforeRestoringAutoCommit() throws Exception {
        var workFailure = new AssertionError("original error");
        assertSame(workFailure, assertThrows(AssertionError.class,
                () -> SqliteTransactions.run(faults.connection, () -> {
                    insert(faults.connection, 7);
                    throw workFailure;
                })));

        assertEquals(List.of(), committedValues());
        assertEquals(1, faults.rollbacks);
        assertTrue(actual.getAutoCommit());
    }

    @Test
    void failedRollbackNeverCommitsTheFailedWorkDuringCleanup() throws Exception {
        var workFailure = new IllegalStateException("original work failure");
        faults.rollbackFailure = new SQLException("rollback failed before reaching SQLite");

        var failure = assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(faults.connection, () -> {
                    insert(faults.connection, 7);
                    throw workFailure;
                }));

        assertSame(workFailure, failure.getCause());
        assertTrue(Arrays.asList(workFailure.getSuppressed()).contains(faults.rollbackFailure));
        assertEquals(0, faults.resets, "Failed rollback must never be followed by setAutoCommit(true)");
        assertEquals(List.of(), committedValues(), "The M0 failure scenario must not commit its write");
        assertTrue(actual.isClosed(), "The unsafe connection must be closed");
        assertThrows(SQLException.class, actual::createStatement);
        assertThrows(SQLException.class, () -> insert(actual, 8));
        assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.requireCommittedReads(faults.connection));
        assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(faults.connection, () -> fail("Quarantined work must not execute")));
    }

    @Test
    void failedCloseKeepsQuarantineAndBlocksPreviouslyCreatedJdbcHandles() throws Exception {
        Connection protectedConnection = SqliteConnectionGuard.protect(faults.connection);
        faults.rollbackFailure = new SQLException("rollback failed");
        faults.closeFailure = new SQLException("physical close failed");
        var workFailure = new IllegalStateException("original work failure");
        try (var cachedStatement = protectedConnection.createStatement();
             var cachedPrepared = protectedConnection.prepareStatement("INSERT INTO entries VALUES(8)");
             var cachedRows = cachedStatement.executeQuery("SELECT COUNT(*) FROM entries")) {
            var metadata = protectedConnection.getMetaData();
            assertSame(protectedConnection, cachedStatement.getConnection());
            assertSame(protectedConnection, cachedPrepared.getConnection());
            assertSame(protectedConnection, metadata.getConnection());

            var failure = assertThrows(CommitOutcomeUnknownException.class,
                    () -> SqliteTransactions.run(protectedConnection, () -> {
                        insert(protectedConnection, 7);
                        throw workFailure;
                    }));

            assertSame(workFailure, failure.getCause());
            assertTrue(Arrays.asList(failure.getSuppressed()).contains(faults.closeFailure));
            assertFalse(actual.isClosed(), "A failed physical close must not be mistaken for rollback");
            assertTrue(protectedConnection.isClosed(), "The guarded handle reports itself unusable");
            assertEquals(0, faults.resets);
            assertEquals(List.of(), committedValues());
            assertThrows(SQLException.class, protectedConnection::createStatement);
            assertThrows(SQLException.class, () -> protectedConnection.prepareStatement("SELECT 1"));
            assertThrows(SQLException.class, protectedConnection::commit);
            assertThrows(SQLException.class, () -> protectedConnection.setAutoCommit(true));
            assertThrows(SQLException.class, () -> cachedStatement.executeUpdate("INSERT INTO entries VALUES(8)"));
            assertThrows(SQLException.class, cachedPrepared::executeUpdate);
            assertThrows(SQLException.class, cachedRows::next);
            assertThrows(SQLException.class, metadata::getConnection);
            assertThrows(CommitOutcomeUnknownException.class,
                    () -> SqliteTransactions.requireCommittedReads(protectedConnection));
            assertThrows(CommitOutcomeUnknownException.class,
                    () -> SqliteTransactions.requireCommittedReads(faults.connection));
            assertThrows(CommitOutcomeUnknownException.class, () -> SqliteConnectionGuard.protect(actual));
            assertThrows(CommitOutcomeUnknownException.class,
                    () -> SqliteTransactions.run(protectedConnection, () -> fail("Quarantine is sticky")));
        }
    }

    @Test
    void lostCommitAcknowledgementDoesNotRepeatTheCommittedWork() throws Exception {
        faults.commitAfterFailure = new SQLException("acknowledgement lost after actual commit");
        var calls = new AtomicInteger();

        var failure = assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(faults.connection, () -> {
                    calls.incrementAndGet();
                    insert(faults.connection, 7);
                    return null;
                }));

        assertSame(faults.commitAfterFailure, failure.getCause());
        assertEquals(1, calls.get());
        assertEquals(1, faults.commits, "The helper never retries commit or the callback");
        assertEquals(List.of(7), committedValues());
        assertFalse(actual.isClosed());
        SqliteTransactions.requireCommittedReads(faults.connection);
    }

    @Test
    void commitFailureBeforeSqliteIsStillReportedAsUnknownWithoutRetry() throws Exception {
        faults.commitBeforeFailure = new SQLException("commit failed before reaching SQLite");
        var failure = assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(faults.connection, () -> {
                    insert(faults.connection, 7);
                    return null;
                }));

        assertSame(faults.commitBeforeFailure, failure.getCause());
        assertEquals(1, faults.commits);
        assertEquals(1, faults.rollbacks);
        assertEquals(List.of(), committedValues());
        assertTrue(actual.getAutoCommit());
    }

    @Test
    void lostCommitAcknowledgementAndFailedRollbackStillPreserveTheCommittedRow() throws Exception {
        faults.commitAfterFailure = new SQLException("commit succeeded but acknowledgement was lost");
        faults.rollbackFailure = new SQLException("recovery rollback failed");

        var failure = assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(faults.connection, () -> {
                    insert(faults.connection, 7);
                    return null;
                }));

        assertSame(faults.commitAfterFailure, failure.getCause());
        assertTrue(Arrays.asList(faults.commitAfterFailure.getSuppressed()).contains(faults.rollbackFailure));
        assertEquals(0, faults.resets);
        assertEquals(1, faults.commits);
        assertEquals(List.of(7), committedValues(), "Closing must not erase an already committed operation");
        assertTrue(actual.isClosed());
    }

    @Test
    void nestedSuccessReleasesOnlyItsSavepointAndCommitsOnceAtTheOuterScope() throws Exception {
        SqliteTransactions.run(faults.connection, () -> {
            insert(faults.connection, 1);
            SqliteTransactions.run(faults.connection, () -> {
                insert(faults.connection, 2);
                return null;
            });
            assertEquals(0, faults.commits);
            assertFalse(faults.connection.getAutoCommit());
            insert(faults.connection, 3);
            return null;
        });

        assertEquals(List.of(1, 2, 3), committedValues());
        assertEquals(1, faults.releases);
        assertEquals(1, faults.commits);
        assertEquals(1, faults.resets);
    }

    @Test
    void successfulSavepointRollbackAllowsTheOuterTransactionToContinue() throws Exception {
        var innerFailure = new IllegalArgumentException("inner work failed");
        SqliteTransactions.run(faults.connection, () -> {
            insert(faults.connection, 1);
            assertSame(innerFailure, assertThrows(IllegalArgumentException.class,
                    () -> SqliteTransactions.run(faults.connection, () -> {
                        insert(faults.connection, 2);
                        throw innerFailure;
                    })));
            // Legitimate active transactions are not poisoned merely by rejecting standalone reads.
            assertThrows(CommitOutcomeUnknownException.class,
                    () -> SqliteTransactions.requireCommittedReads(faults.connection));
            assertFalse(faults.connection.getAutoCommit());
            insert(faults.connection, 3);
            return null;
        });

        assertEquals(List.of(1, 3), committedValues());
        assertEquals(1, faults.savepointRollbacks);
        assertEquals(1, faults.commits);
        assertEquals(1, faults.resets);
    }

    @Test
    void failedSavepointRollbackClosesAndRollsBackTheEntireOuterTransaction() throws Exception {
        faults.savepointRollbackFailure = new SQLException("savepoint rollback failed");
        var innerFailure = new IllegalStateException("inner work failed");

        assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(faults.connection, () -> {
                    insert(faults.connection, 1);
                    SqliteTransactions.run(faults.connection, () -> {
                        insert(faults.connection, 2);
                        throw innerFailure;
                    });
                    return null;
                }));

        assertTrue(actual.isClosed());
        assertEquals(List.of(), committedValues());
        assertEquals(0, faults.commits);
        assertEquals(0, faults.resets);
        assertTrue(Arrays.asList(innerFailure.getSuppressed()).contains(faults.savepointRollbackFailure));
    }

    @Test
    void caughtSavepointRollbackFailureAndFailedCloseCannotLetTheOuterScopeCommit() throws Exception {
        faults.savepointRollbackFailure = new SQLException("savepoint rollback failed");
        faults.closeFailure = new SQLException("close failed");
        Connection protectedConnection = SqliteConnectionGuard.protect(faults.connection);
        var caught = new ArrayList<CommitOutcomeUnknownException>();

        var outerFailure = assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(protectedConnection, () -> {
                    insert(protectedConnection, 1);
                    caught.add(assertThrows(CommitOutcomeUnknownException.class,
                            () -> SqliteTransactions.run(protectedConnection, () -> {
                                insert(protectedConnection, 2);
                                throw new IllegalStateException("inner work failed");
                            })));
                    return "caller tried to ignore inner failure";
                }));

        assertSame(caught.getFirst(), outerFailure.getCause());
        assertFalse(actual.isClosed());
        assertEquals(0, faults.commits, "Poison must be checked again before the outer commit");
        assertEquals(0, faults.resets);
        assertEquals(List.of(), committedValues());
        assertThrows(SQLException.class, protectedConnection::commit);
        assertThrows(SQLException.class, () -> protectedConnection.setAutoCommit(true));
    }

    @Test
    void savepointReleaseFailureBeforeExecutionCanBeRolledBackAndReportedToTheOuterCaller() throws Exception {
        faults.releaseBeforeFailure = new SQLException("savepoint release failed");
        SqliteTransactions.run(faults.connection, () -> {
            insert(faults.connection, 1);
            var failure = assertThrows(CommitOutcomeUnknownException.class,
                    () -> SqliteTransactions.run(faults.connection, () -> {
                        insert(faults.connection, 2);
                        return null;
                    }));
            assertSame(faults.releaseBeforeFailure, failure.getCause());
            insert(faults.connection, 3);
            return null;
        });

        assertEquals(List.of(1, 3), committedValues());
        assertEquals(1, faults.savepointRollbacks);
        assertEquals(1, faults.commits);
    }

    @Test
    void savepointReleaseAcknowledgementLossQuarantinesWhenRollbackCanNoLongerFindTheSavepoint() throws Exception {
        faults.releaseAfterFailure = new SQLException("savepoint release acknowledgement lost");
        assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(faults.connection, () -> {
                    insert(faults.connection, 1);
                    assertThrows(CommitOutcomeUnknownException.class,
                            () -> SqliteTransactions.run(faults.connection, () -> {
                                insert(faults.connection, 2);
                                return null;
                            }));
                    return null;
                }));

        assertTrue(actual.isClosed());
        assertEquals(List.of(), committedValues());
        assertEquals(0, faults.commits);
        assertEquals(0, faults.resets);
        assertEquals(1, faults.releaseAfterFailure.getSuppressed().length);
    }

    @Test
    void cleanupFailureDoesNotHideTheOriginalWorkAndCloseFailures() throws Exception {
        var workFailure = new IllegalStateException("original work failure");
        faults.resetFailure = new SQLException("auto-commit reset failed");
        faults.closeFailure = new SQLException("close also failed");
        Connection protectedConnection = SqliteConnectionGuard.protect(faults.connection);

        var failure = assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(protectedConnection, () -> {
                    insert(protectedConnection, 7);
                    throw workFailure;
                }));

        assertSame(workFailure, failure.getCause());
        assertTrue(Arrays.asList(workFailure.getSuppressed()).contains(faults.resetFailure));
        assertTrue(Arrays.asList(failure.getSuppressed()).contains(faults.closeFailure));
        assertEquals(List.of(), committedValues());
        assertThrows(SQLException.class, protectedConnection::createStatement);
    }

    @Test
    void cleanupFailureAfterConfirmedCommitPreservesDataButQuarantinesTheConnection() throws Exception {
        faults.resetFailure = new SQLException("auto-commit reset failed after commit");
        var failure = assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(faults.connection, () -> {
                    insert(faults.connection, 7);
                    return null;
                }));

        assertSame(faults.resetFailure, failure.getCause());
        assertEquals(List.of(7), committedValues());
        assertEquals(1, faults.commits);
        assertTrue(actual.isClosed());
        assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.requireCommittedReads(faults.connection));
    }

    @Test
    void cleanupFailureAfterUnknownCommitKeepsBothErrorsAndNeverRetries() throws Exception {
        faults.commitAfterFailure = new SQLException("actual commit succeeded but acknowledgement was lost");
        faults.resetFailure = new SQLException("auto-commit reset also failed");
        var failure = assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(faults.connection, () -> {
                    insert(faults.connection, 7);
                    return null;
                }));

        assertSame(faults.commitAfterFailure, failure.getCause().getCause());
        assertTrue(Arrays.asList(failure.getCause().getSuppressed()).contains(faults.resetFailure));
        assertEquals(List.of(7), committedValues());
        assertEquals(1, faults.commits);
        assertTrue(actual.isClosed());
    }

    @Test
    void resetAcknowledgementAndCloseFailuresCannotRestoreTrustEvenWhenAutoCommitIsTrue() throws Exception {
        faults.resetAfterFailure = new SQLException("reset succeeded but acknowledgement was lost");
        faults.closeFailure = new SQLException("physical close failed");
        Connection protectedConnection = SqliteConnectionGuard.protect(faults.connection);

        var failure = assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(protectedConnection, () -> {
                    insert(protectedConnection, 7);
                    return null;
                }));

        assertTrue(actual.getAutoCommit(), "The driver really applied the reset before throwing");
        assertFalse(actual.isClosed());
        assertSame(faults.resetAfterFailure, failure.getCause());
        assertTrue(Arrays.asList(failure.getSuppressed()).contains(faults.closeFailure));
        assertEquals(List.of(7), committedValues());
        assertEquals(1, faults.commits);
        assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.requireCommittedReads(protectedConnection));
        assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(protectedConnection, () -> fail("Auto-commit cannot clear quarantine")));
        assertThrows(SQLException.class, protectedConnection::createStatement);
    }

    @Test
    void uncertainBeginClosesWithoutRunningWorkOrResettingAutoCommit() throws Exception {
        faults.beginFailure = new SQLException("begin failed before reaching SQLite");
        var failure = assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(faults.connection, () -> fail("Work must not run after failed setup")));

        assertSame(faults.beginFailure, failure.getCause());
        assertTrue(actual.isClosed());
        assertEquals(0, faults.resets);
        assertEquals(List.of(), committedValues());
    }

    @Test
    void uncertainSavepointCreationPoisonsTheOuterTransaction() throws Exception {
        faults.savepointFailure = new SQLException("savepoint creation failed");
        assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(faults.connection, () -> {
                    insert(faults.connection, 1);
                    assertThrows(CommitOutcomeUnknownException.class,
                            () -> SqliteTransactions.run(faults.connection, () -> fail("Inner work must not run")));
                    return null;
                }));

        assertTrue(actual.isClosed());
        assertEquals(0, faults.commits);
        assertEquals(0, faults.resets);
        assertEquals(List.of(), committedValues());
    }

    @Test
    void failureToInspectTransactionStateQuarantinesWithoutRunningWork() throws Exception {
        faults.autoCommitReadFailure = new SQLException("cannot read auto-commit state");
        var failure = assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(faults.connection, () -> fail("Unknown connection must not run work")));

        assertSame(faults.autoCommitReadFailure, failure.getCause());
        assertTrue(actual.isClosed());
        assertEquals(0, faults.resets);
        assertEquals(List.of(), committedValues());
    }

    @Test
    void committedReadGuardQuarantinesOnDriverStateReadFailure() {
        faults.autoCommitReadFailure = new SQLException("cannot verify committed state");
        var failure = assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.requireCommittedReads(faults.connection));

        assertSame(faults.autoCommitReadFailure, failure.getCause());
        assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(faults.connection, () -> fail("Read failure must be sticky")));
    }

    @Test
    void healthyGuardPreservesJdbcOperationsAndDoesNotExposeAnUnguardedConnection() throws Exception {
        Connection protectedConnection = SqliteConnectionGuard.protect(faults.connection);
        assertSame(protectedConnection, protectedConnection.unwrap(Connection.class));
        assertTrue(protectedConnection.isWrapperFor(Connection.class));
        assertSame(protectedConnection, protectedConnection.getMetaData().getConnection());
        SqliteTransactions.run(protectedConnection, () -> {
            try (var insert = protectedConnection.prepareStatement("INSERT INTO entries VALUES(?)")) {
                insert.setInt(1, 7);
                insert.executeUpdate();
                assertSame(protectedConnection, insert.getConnection());
            }
            return null;
        });
        try (var query = protectedConnection.prepareStatement("SELECT value FROM entries");
             var rows = query.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(7, rows.getInt(1));
            assertSame(protectedConnection, rows.getStatement().getConnection());
        }
        assertEquals(List.of(7), committedValues());
        assertFalse(protectedConnection.isClosed());
    }

    @Test
    void freshConnectionCanProceedAfterAnUntrustedConnectionWasClosed() throws Exception {
        faults.rollbackFailure = new SQLException("rollback failed");
        assertThrows(CommitOutcomeUnknownException.class,
                () -> SqliteTransactions.run(faults.connection, () -> {
                    insert(faults.connection, 7);
                    throw new IllegalStateException("work failed");
                }));

        try (var fresh = SqliteConnectionGuard.protect(DriverManager.getConnection("jdbc:sqlite:" + file))) {
            SqliteTransactions.requireCommittedReads(fresh);
            SqliteTransactions.run(fresh, () -> {
                insert(fresh, 8);
                return null;
            });
        }
        assertEquals(List.of(8), committedValues());
    }

    @Test
    void guardedConnectionSupportsTheExistingSchemaInitializationAndQueries() throws Exception {
        // Same connection protection used by EconomyDatabase, without loading Minecraft server classes.
        try (var connection = SqliteConnectionGuard.protect(
                DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("guarded-schema.sqlite")))) {
            EconomySchema.initialize(connection);
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> connection.unwrap(org.sqlite.SQLiteConnection.class),
                    "The guarded connection must not expose its unguarded driver");
            SqliteTransactions.requireCommittedReads(connection);
            assertEquals(EconomySchema.VERSION, SqliteTransactions.run(connection, () -> {
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT version FROM economy_schema_migrations")) {
                    assertTrue(rows.next());
                    return rows.getInt(1);
                }
            }));
            EconomySchema.initialize(connection);
        }
    }

    private static void insert(Connection connection, int value) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO entries VALUES(?)")) {
            statement.setInt(1, value);
            statement.executeUpdate();
        }
    }

    private List<Integer> committedValues() throws SQLException {
        try (var observer = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = observer.createStatement();
             var rows = statement.executeQuery("SELECT value FROM entries ORDER BY value")) {
            List<Integer> values = new ArrayList<>();
            while (rows.next()) values.add(rows.getInt(1));
            return values;
        }
    }

    /** Only inject JDBC faults; all SQL and transaction effects execute in the real SQLite driver. */
    private static final class Faults implements InvocationHandler {
        private final Connection delegate;
        final Connection connection;
        SQLException rollbackFailure, savepointRollbackFailure, closeFailure, resetFailure, resetAfterFailure;
        SQLException commitBeforeFailure, commitAfterFailure, releaseBeforeFailure, releaseAfterFailure;
        SQLException beginFailure, savepointFailure, autoCommitReadFailure;
        int commits, rollbacks, savepointRollbacks, resets, releases;

        private Faults(Connection delegate) {
            this.delegate = delegate;
            connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Fault-injecting SQLite connection";
                    default -> throw new UnsupportedOperationException(name);
                };
            }
            switch (name) {
                case "commit" -> {
                    commits++;
                    if (commitBeforeFailure != null) throw commitBeforeFailure;
                }
                case "rollback" -> {
                    if (arguments == null || arguments.length == 0) {
                        rollbacks++;
                        if (rollbackFailure != null) throw rollbackFailure;
                    } else {
                        savepointRollbacks++;
                        if (savepointRollbackFailure != null) throw savepointRollbackFailure;
                    }
                }
                case "setAutoCommit" -> {
                    if (Boolean.TRUE.equals(arguments[0])) {
                        resets++;
                        if (resetFailure != null) throw resetFailure;
                    } else if (beginFailure != null) throw beginFailure;
                }
                case "releaseSavepoint" -> {
                    releases++;
                    if (releaseBeforeFailure != null) throw releaseBeforeFailure;
                }
                case "setSavepoint" -> {
                    if (savepointFailure != null) throw savepointFailure;
                }
                case "getAutoCommit" -> {
                    if (autoCommitReadFailure != null) throw autoCommitReadFailure;
                }
                case "close" -> {
                    if (closeFailure != null) throw closeFailure;
                }
            }
            Object result;
            try {
                result = method.invoke(delegate, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
            if (name.equals("commit") && commitAfterFailure != null) throw commitAfterFailure;
            if (name.equals("releaseSavepoint") && releaseAfterFailure != null) throw releaseAfterFailure;
            if (name.equals("setAutoCommit") && Boolean.TRUE.equals(arguments[0]) && resetAfterFailure != null)
                throw resetAfterFailure;
            return result;
        }
    }
}
