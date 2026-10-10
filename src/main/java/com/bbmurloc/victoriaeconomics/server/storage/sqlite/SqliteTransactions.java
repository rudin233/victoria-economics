package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import java.sql.*;

/** Shared SQLite transaction/savepoint helper. Commit acknowledgement failures remain distinguishable. */
public final class SqliteTransactions {
    private SqliteTransactions() {}
    @FunctionalInterface
    public interface Work<T> { T run() throws SQLException; }

    /** Standalone authority queries must never mistake an unresolved local transaction for a committed fact. */
    public static void requireCommittedReads(Connection connection) {
        try {
            if (!connection.getAutoCommit())
                throw new CommitOutcomeUnknownException("SQLite transaction is unresolved; committed production facts cannot be confirmed", null);
        } catch (SQLException failure) {
            throw new CommitOutcomeUnknownException("Cannot confirm SQLite transaction state", failure);
        }
    }

    public static <T> T run(Connection connection, Work<T> work) {
        boolean own = false;
        Savepoint point = null;
        boolean committing = false;
        try {
            own = connection.getAutoCommit();
            if (own) connection.setAutoCommit(false);
            else point = connection.setSavepoint();
            T result = work.run();
            committing = true;
            if (own) connection.commit();
            else connection.releaseSavepoint(point);
            return result;
        } catch (SQLException | RuntimeException failure) {
            try {
                if (own) connection.rollback();
                else if (point != null) connection.rollback(point);
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
                throw new CommitOutcomeUnknownException("SQLite transaction outcome is unknown", failure);
            }
            if (committing) throw new CommitOutcomeUnknownException("SQLite commit acknowledgement failed", failure);
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("SQLite transaction failed", failure);
        } finally {
            if (own) try { connection.setAutoCommit(true); }
            catch (SQLException cleanup) {
                throw new CommitOutcomeUnknownException("SQLite transaction cleanup failed; verify commit", cleanup);
            }
        }
    }
}
