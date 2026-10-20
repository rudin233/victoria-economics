package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import java.sql.*;
import org.sqlite.SQLiteConfig.TransactionMode;

/** Shared SQLite transaction/savepoint helper. Commit acknowledgement failures remain distinguishable. */
public final class SqliteTransactions {
    private SqliteTransactions() {}
    @FunctionalInterface
    public interface Work<T> { T run() throws SQLException; }

    @FunctionalInterface
    public interface WriteWork<T> { T run(WriteSession session) throws SQLException; }

    @FunctionalInterface
    public interface ReadWork<T> { T run(ReadSession session) throws SQLException; }

    /** Explicit snapshot read capability. Cannot be used as a write capability. */
    public static final class ReadSession {
        private final Connection connection;
        private final Thread owner = Thread.currentThread();
        private boolean active = true;
        private ReadSession(Connection connection) { this.connection = connection; }
        public void requireConnection(Connection candidate) {
            if (!active || Thread.currentThread() != owner || candidate != connection)
                throw new IllegalStateException("Invalid SQLite read session");
            SqliteConnectionGuard.requireTrusted(connection);
        }
    }

    public static <T> T read(Connection connection, ReadWork<T> work) {
        requireCommittedReads(connection);
        var config = SqliteConnectionGuard.transactionConfig(connection);
        TransactionMode previous = config.getTransactionMode();
        config.setTransactionMode(TransactionMode.DEFERRED);
        try {
            return run(connection, () -> {
                var session = new ReadSession(connection);
                try { return work.run(session); }
                finally { session.active = false; }
            });
        } finally { config.setTransactionMode(previous); }
    }

    /** Unforgeable, thread-confined capability for explicit transaction-local repository access. */
    public static final class WriteSession {
        private final Connection connection;
        private final Thread owner = Thread.currentThread();
        private boolean active = true;
        private Throwable failed;

        private WriteSession(Connection connection) { this.connection = connection; }

        public void requireConnection(Connection candidate) {
            if (!active || owner != Thread.currentThread()) throw new IllegalStateException("SQLite write session is closed or belongs to another thread");
            if (candidate != connection) throw new IllegalArgumentException("Repository belongs to another SQLite connection");
            SqliteConnectionGuard.requireTrusted(connection);
            if (failed != null) throw new IllegalStateException("SQLite write session has failed", failed);
        }

        /** A caught repository operation failure still prevents this physical transaction from committing. */
        public <T> T perform(Work<T> operation) throws SQLException {
            requireConnection(connection);
            try { return operation.run(); }
            catch (SQLException | RuntimeException | Error failure) { failed = failure; throw failure; }
        }
    }

    /** Owns an IMMEDIATE JDBC transaction; nesting cannot establish the required lock-before-read order. */
    public static <T> T write(Connection connection, WriteWork<T> work) {
        requireCommittedReads(connection);
        var config = SqliteConnectionGuard.transactionConfig(connection);
        TransactionMode previous = config.getTransactionMode();
        config.setTransactionMode(TransactionMode.IMMEDIATE);
        try {
            return run(connection, () -> {
                // Xerial 3.46.1.0 already executed BEGIN IMMEDIATE in setAutoCommit(false).
                // commit()/rollback() issue another BEGIN: use DEFERRED for that empty transaction so
                // cleanup cannot acquire a second write lock after the business transaction ends.
                config.setTransactionMode(TransactionMode.DEFERRED);
                var session = new WriteSession(connection);
                try {
                    T result = work.run(session);
                    session.requireConnection(connection);
                    return result;
                }
                finally { session.active = false; }
            });
        } finally {
            // Local driver configuration only, including after quarantine; no JDBC reset/SQL here.
            config.setTransactionMode(previous);
        }
    }

    /** Standalone authority queries must never mistake an unresolved local transaction for a committed fact. */
    public static void requireCommittedReads(Connection connection) {
        SqliteConnectionGuard.requireTrusted(connection);
        boolean autoCommit;
        try {
            autoCommit = connection.getAutoCommit();
        } catch (SQLException | RuntimeException failure) {
            var unknown = new CommitOutcomeUnknownException("Cannot confirm SQLite transaction state", failure);
            SqliteConnectionGuard.quarantine(connection, unknown);
            throw unknown;
        }
        if (!autoCommit)
            throw new CommitOutcomeUnknownException("SQLite transaction is unresolved; committed production facts cannot be confirmed", null);
    }

    public static <T> T run(Connection connection, Work<T> work) {
        SqliteConnectionGuard.requireTrusted(connection);
        boolean own = false;
        boolean started = false;
        Savepoint point = null;
        boolean committing = false;
        T result = null;
        Throwable pending = null;
        try {
            own = connection.getAutoCommit();
            if (own) connection.setAutoCommit(false);
            else point = connection.setSavepoint();
            started = true;
            result = work.run();
            // An inner scope may have failed and been caught by the callback. It must still poison this commit.
            SqliteConnectionGuard.requireTrusted(connection);
            committing = true;
            if (own) connection.commit();
            else connection.releaseSavepoint(point);
        } catch (SQLException | RuntimeException | Error failure) {
            pending = failure;
            if (SqliteConnectionGuard.isQuarantined(connection)) {
                if (!(failure instanceof CommitOutcomeUnknownException))
                    pending = new CommitOutcomeUnknownException("SQLite connection was quarantined during the transaction", failure);
            } else if (!started) {
                pending = new CommitOutcomeUnknownException("Cannot confirm SQLite transaction setup", failure);
                SqliteConnectionGuard.quarantine(connection, pending);
            } else {
                try {
                    if (own) connection.rollback();
                    else connection.rollback(point);
                } catch (SQLException | RuntimeException | Error rollback) {
                    if (failure != rollback) failure.addSuppressed(rollback);
                    pending = new CommitOutcomeUnknownException(own
                            ? "SQLite rollback failed; transaction outcome is unknown"
                            : "SQLite savepoint rollback failed; outer transaction is unsafe", failure);
                    SqliteConnectionGuard.quarantine(connection, pending);
                }
                if (committing && !SqliteConnectionGuard.isQuarantined(connection))
                    pending = new CommitOutcomeUnknownException(own
                            ? "SQLite commit acknowledgement failed; verify durable state before retrying"
                            : "SQLite savepoint release acknowledgement failed", failure);
            }
        }
        // Only a confirmed commit or rollback permits reset: JDBC reset can itself commit.
        if (own && started && !SqliteConnectionGuard.isQuarantined(connection)) {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException | RuntimeException | Error cleanup) {
                if (pending == null) pending = cleanup;
                else if (pending != cleanup) pending.addSuppressed(cleanup);
                pending = new CommitOutcomeUnknownException("SQLite transaction cleanup failed; verify durable state", pending);
                SqliteConnectionGuard.quarantine(connection, pending);
            }
        }
        if (pending instanceof RuntimeException runtime) throw runtime;
        if (pending instanceof Error error) throw error;
        if (pending != null) throw new IllegalStateException("SQLite transaction failed", pending);
        return result;
    }
}
