package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.EconomyCheckpoint.Receipt;

/** One complete image per physical SQLite transaction. No business service reads this stale file. */
public final class SqliteCheckpointStore {
    private final Path file;
    private final Supplier<Connection> writer;
    public record Timing(long rows, long validateNanos, long openNanos, long writeTransactionNanos,
                         long outcomeVerificationNanos, long totalNanos) {}
    private final List<Timing> timings = new ArrayList<>();
    public synchronized List<Timing> timings() { return List.copyOf(timings); }
    public SqliteCheckpointStore(Path file) { this(file, () -> {
        try { return SqliteEconomyDatabase.open(file); }
        catch (RuntimeException preflight) { return CheckpointFileRecovery.writableAfterVerifiedRecovery(file, preflight); }
    }); }
    SqliteCheckpointStore(Path file, Supplier<Connection> writer) { this.file = file; this.writer = writer; }

    public EconomyCheckpoint.Recovered load() {
        try (var c = SqliteEconomyDatabase.openReadOnly(file)) { return EconomyCheckpoint.readVerified(c); }
        catch (SQLException failure) { throw new IllegalStateException("Cannot read trusted checkpoint", failure); }
        catch (RuntimeException preflight) { return CheckpointFileRecovery.readCopy(file, preflight); }
    }

    public Receipt save(Receipt previous, Receipt next, EconomyCheckpoint snapshot) {
        long began = System.nanoTime();
        EconomyCheckpoint.requireMatches(next, snapshot);
        if (previous != null && (!next.worldId().equals(previous.worldId()) || next.sequence() <= previous.sequence()
                || next.economicTick() < previous.economicTick())) throw new IllegalArgumentException("Checkpoint order regression");
        snapshot.validate(); // private memory image; includes full historical ledger/payment validation
        long validated = System.nanoTime(), opened = validated;
        long[] writeNanos = {0}, verificationNanos = {0};
        Connection c = null;
        try {
            c = writer.get();
            opened = System.nanoTime();
            Connection target = c;
            long writing = System.nanoTime();
            try {
                SqliteTransactions.write(target, tx -> {
                    if (!EconomyCheckpoint.receipt(target).equals(Optional.ofNullable(previous)))
                        throw new IllegalStateException("Disk checkpoint CAS conflict; another writer or unverified generation");
                    snapshot.install(target, tx);
                    EconomyCheckpoint.saveReceipt(target, tx, next);
                    return null;
                });
            } finally { writeNanos[0] = System.nanoTime() - writing; }
            return next;
        } catch (CommitOutcomeUnknownException unknown) {
            if (c != null) SqliteConnectionGuard.quarantine(c, unknown);
            // Never infer failure from rollback or automatically submit the write again.
            EconomyCheckpoint.Recovered verified;
            long verifying = System.nanoTime();
            try { verified = load(); }
            catch (RuntimeException verification) {
                unknown.addSuppressed(verification);
                throw new CommitOutcomeUnknownException("Checkpoint outcome cannot be verified; writer is blocked", unknown);
            }
            finally { verificationNanos[0] = System.nanoTime() - verifying; }
            if (verified.receipt().equals(next)) return next;
            if (Objects.equals(verified.receipt(), previous))
                throw new IllegalStateException("Checkpoint was not saved; previous complete checkpoint verified", unknown);
            throw new CommitOutcomeUnknownException("Unexpected checkpoint identity; writer is blocked", unknown);
        } finally {
            if (c != null) try { c.close(); }
            catch (SQLException failure) { SqliteConnectionGuard.quarantine(c, failure); }
            synchronized (this) {
                if (timings.size() == 4096) timings.removeFirst();
                timings.add(new Timing(snapshot.rowCount(), validated - began, opened - validated,
                        writeNanos[0], verificationNanos[0], System.nanoTime() - began));
            }
        }
    }
}
