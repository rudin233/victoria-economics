package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import java.sql.*;

/** Shared SQLite transaction/savepoint helper. Commit acknowledgement failures remain distinguishable. */
public final class SqliteTransactions {
    private SqliteTransactions() {}
    @FunctionalInterface
    public interface Work<T> { T run() throws SQLException; }

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
