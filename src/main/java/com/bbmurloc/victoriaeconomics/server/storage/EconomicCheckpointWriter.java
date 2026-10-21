package com.bbmurloc.victoriaeconomics.server.storage;

import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.EconomyCheckpoint.Receipt;
import java.util.*;
import java.util.concurrent.*;

/** Single ordered writer, one in-flight + one replaceable whole-world snapshot. */
public final class EconomicCheckpointWriter implements AutoCloseable {
    @FunctionalInterface public interface Persistence {
        Receipt save(Receipt previous, Receipt next, EconomyCheckpoint snapshot);
    }
    /** commitNanos covers each complete persistence job (including validation/open), not just fsync. */
    public record Status(Receipt committed, Long inFlightSequence, Long pendingSequence,
                         long saved, long coalesced, long failed, String lastFailure,
                         List<Long> commitNanos, boolean blocked) {}
    private record Request(Receipt identity, EconomyCheckpoint snapshot, CompletableFuture<Receipt> completion) {}
    private final Persistence persistence;
    private final UUID worldId;
    private final Thread worker;
    private final List<Long> commitNanos = new ArrayList<>();
    private Receipt committed;
    private Request pending, inFlight;
    private long sequence, saved, coalesced, failed;
    private String lastFailure;
    private boolean closed, blocked;

    public EconomicCheckpointWriter(Receipt initial, Persistence persistence) {
        committed = Objects.requireNonNull(initial); worldId = initial.worldId(); sequence = initial.sequence();
        this.persistence = persistence;
        worker = new Thread(this::run, "VE-economic-checkpoint"); worker.setDaemon(true); worker.start();
    }
    public synchronized CompletableFuture<Receipt> submit(EconomyCheckpoint snapshot) {
        if (closed || blocked) throw new IllegalStateException("Economic checkpoint writer is closed or outcome is unresolved");
        if (snapshot.economicTick() < committed.economicTick()) throw new IllegalArgumentException("Checkpoint tick regression");
        // A saved identical image already proves the request, without another disk commit.
        if (pending == null && inFlight == null && snapshot.sha256().equals(committed.sha256()))
            return CompletableFuture.completedFuture(committed);
        if (pending != null && snapshot.economicTick() < pending.snapshot.economicTick()
                || inFlight != null && snapshot.economicTick() < inFlight.snapshot.economicTick())
            throw new IllegalArgumentException("Out-of-order snapshot");
        var receipt = new Receipt(worldId, UUID.randomUUID(), Math.incrementExact(sequence), snapshot.economicTick(), snapshot.sha256());
        sequence = receipt.sequence();
        var request = new Request(receipt, snapshot, pending == null ? new CompletableFuture<>() : pending.completion);
        if (pending != null) {
            // Replaced requests share one completion: no unbounded chain of merge callbacks.
            coalesced = Math.incrementExact(coalesced);
        }
        pending = request; notifyAll(); return request.completion.copy();
    }
    private void run() {
        while (true) {
            Request request; Receipt previous;
            synchronized (this) {
                while (pending == null && !closed) try { wait(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                if (pending == null) return;
                request = pending; pending = null; inFlight = request; previous = committed;
            }
            long began = System.nanoTime();
            Receipt result = null; Throwable failure = null; Request rejected = null;
            try {
                result = persistence.save(previous, request.identity, request.snapshot);
                if (!request.identity.equals(result)) throw new CommitOutcomeUnknownException("Writer returned an unrelated checkpoint receipt", null);
            }
            catch (Throwable caught) { failure = caught; }
            synchronized (this) {
                if (commitNanos.size() == 4096) commitNanos.removeFirst();
                commitNanos.add(System.nanoTime() - began);
                inFlight = null;
                if (failure == null) {
                    committed = result; saved = Math.incrementExact(saved); lastFailure = null;
                } else {
                    failed = Math.incrementExact(failed); lastFailure = failure.toString();
                    if (failure instanceof CommitOutcomeUnknownException || failure instanceof Error) {
                        blocked = true;
                        rejected = pending;
                        pending = null;
                    }
                }
                notifyAll();
            }
            if (rejected != null) rejected.completion.completeExceptionally(failure);
            if (failure == null) request.completion.complete(result);
            else request.completion.completeExceptionally(failure);
        }
    }
    public synchronized Status status() {
        return new Status(committed, inFlight == null ? null : inFlight.identity.sequence(), pending == null ? null : pending.identity.sequence(),
                saved, coalesced, failed, lastFailure, List.copyOf(commitNanos), blocked);
    }
    public static Receipt await(CompletableFuture<Receipt> completion) {
        try { return completion.get(30, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Economic save interrupted; not confirmed", failure); }
        catch (ExecutionException | TimeoutException failure) { throw new IllegalStateException("Economic checkpoint save not confirmed; previous checkpoint retained", failure); }
    }
    @Override public void close() {
        synchronized (this) { closed = true; notifyAll(); }
        try { worker.join(30_000); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Checkpoint writer stop interrupted", failure); }
        if (worker.isAlive()) throw new IllegalStateException("Checkpoint writer did not drain; final economic save is NOT confirmed");
    }
}
