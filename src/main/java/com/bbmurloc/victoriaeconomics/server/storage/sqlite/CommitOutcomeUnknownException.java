package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

/** A durable commit may have succeeded; callers must verify authority before compensation. */
public final class CommitOutcomeUnknownException extends IllegalStateException {
    public CommitOutcomeUnknownException(String message, Throwable cause) { super(message, cause); }
}
