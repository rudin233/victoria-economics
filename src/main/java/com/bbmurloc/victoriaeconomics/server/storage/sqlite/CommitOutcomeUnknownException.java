package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

/**
 * Commit, rollback or cleanup could not be confirmed. A durable commit may have succeeded;
 * callers must verify authority on a trusted connection before compensation or retry.
 */
public final class CommitOutcomeUnknownException extends IllegalStateException {
    public CommitOutcomeUnknownException(String message, Throwable cause) { super(message, cause); }
}
