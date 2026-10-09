package com.bbmurloc.victoriaeconomics.server.production.domain;

import java.util.*;

/**
 * One building's execution aggregate. Settlement and boundary work continue to block new starts.
 */
public final class ProductionExecution {
    public enum Boundary {NONE, METHODS, EQUIPMENT, WORKFORCE}

    private final UUID buildingId;
    private ProductionBatch batch;
    private ProductionBatch lastBatch;
    private Boundary boundary = Boundary.NONE;
    private boolean automatic;

    public ProductionExecution(UUID buildingId) {
        this.buildingId = Objects.requireNonNull(buildingId);
    }

    public ProductionBatch batch() {
        return batch;
    }

    public ProductionBatch lastBatch() {
        return lastBatch;
    }

    public Boundary boundary() {
        return boundary;
    }

    public boolean hasUnfinishedBatch() {
        return batch != null && !batch.isEnded();
    }

    public boolean blocksConfiguration() {
        return hasUnfinishedBatch() || boundary != Boundary.NONE;
    }

    public boolean automatic() {
        return automatic;
    }

    public void requestAutomatic(boolean enabled) {
        automatic = enabled;
    }

    /**
     * Opaque rollback checkpoint: callers cannot fabricate production progress or status.
     */
    public static final class State {
        private final ProductionExecution owner;
        private final ProductionBatch batch, lastBatch;
        private final Boundary boundary;
        private final boolean automatic;

        private State(ProductionExecution owner) {
            this.owner = owner;
            batch = owner.batch;
            lastBatch = owner.lastBatch;
            boundary = owner.boundary;
            automatic = owner.automatic;
        }

        public ProductionBatch batch() {
            return batch;
        }

        public ProductionBatch lastBatch() {
            return lastBatch;
        }

        public Boundary boundary() {
            return boundary;
        }

        public boolean automatic() {
            return automatic;
        }
    }

    public State state() {
        return new State(this);
    }

    /**
     * Rollback of a failed durable application command, never a player control.
     */
    public void restoreState(State state) {
        if (state.owner != this) throw new IllegalArgumentException("Foreign production rollback checkpoint");
        ProductionExecution validated = new ProductionExecution(buildingId);
        validated.restore(state.batch(), state.lastBatch(), state.boundary(), state.automatic());
        batch = validated.batch;
        lastBatch = validated.lastBatch;
        boundary = validated.boundary;
        automatic = validated.automatic;
    }

    public ProductionBatch start(UUID id, ProductionBatchConfiguration configuration) {
        if (batch != null || boundary != Boundary.NONE)
            throw new IllegalStateException("Previous production has not finished");
        batch = new ProductionBatch(id, buildingId, configuration, 0, ProductionBatch.Status.ACTIVE);
        return batch;
    }

    public void tick(long ticks) {
        if (ticks < 0) throw new IllegalArgumentException("Negative economic ticks");
        if (batch == null || !batch.isActive()) return;
        long remaining = batch.getMaximumProgressUnits() - batch.getProgressUnits();
        int speed = batch.getConfiguration().workforcePlan().speedNumerator();
        long delta = ticks > remaining / speed ? remaining : Math.min(remaining, ticks * speed);
        long progress = batch.getProgressUnits() + delta;
        change(progress == batch.getMaximumProgressUnits() ? ProductionBatch.Status.SETTLING_COMPLETED : ProductionBatch.Status.ACTIVE, progress);
    }

    public void pause() {
        if (batch != null && batch.isActive()) change(ProductionBatch.Status.PAUSED, batch.getProgressUnits());
    }

    public void resume() {
        if (batch != null && batch.getStatus() == ProductionBatch.Status.PAUSED)
            change(ProductionBatch.Status.ACTIVE, batch.getProgressUnits());
    }

    public void terminate() {
        automatic = false;
        if (batch != null && !batch.isEnded() && !batch.needsSettlement()) {
            change(ProductionBatch.Status.SETTLING_ABORTED, batch.getProgressUnits());
        }
    }

    public void settlementCompleted(UUID batchId) {
        if (batch == null || !batch.getId().equals(batchId)) throw new IllegalStateException("Wrong settlement owner");
        if (batch.isEnded()) return;
        if (!batch.needsSettlement()) throw new IllegalStateException("Production has not reached settlement");
        change(batch.getStatus() == ProductionBatch.Status.SETTLING_COMPLETED ? ProductionBatch.Status.COMPLETED : ProductionBatch.Status.ABORTED, batch.getProgressUnits());
        boundary = Boundary.METHODS;
    }

    public void methodsApplied() {
        requireBoundary(Boundary.METHODS);
        boundary = Boundary.EQUIPMENT;
    }

    public void revisitMethods() {
        if (boundary == Boundary.NONE || batch == null || !batch.isEnded())
            throw new IllegalStateException("No settled batch boundary");
        boundary = Boundary.METHODS;
    }

    public void equipmentApplied() {
        requireBoundary(Boundary.EQUIPMENT);
        boundary = Boundary.WORKFORCE;
    }

    public void workforceReconciled() {
        requireBoundary(Boundary.WORKFORCE);
        lastBatch = batch;
        batch = null;
        boundary = Boundary.NONE;
    }

    private void requireBoundary(Boundary expected) {
        if (boundary != expected || batch == null || !batch.isEnded())
            throw new IllegalStateException("Unexpected production boundary: " + boundary);
    }

    private void change(ProductionBatch.Status status, long progress) {
        batch = new ProductionBatch(batch.getId(), buildingId, batch.getConfiguration(), progress, status);
    }

    public void restore(ProductionBatch batch, ProductionBatch lastBatch, Boundary boundary, boolean automatic) {
        if (this.batch != null || this.lastBatch != null || this.boundary != Boundary.NONE)
            throw new IllegalStateException("Execution already initialized");
        if (batch != null && !batch.getBuildingId().equals(buildingId))
            throw new IllegalArgumentException("Foreign batch");
        if ((batch != null && batch.isEnded()) != (boundary != Boundary.NONE))
            throw new IllegalArgumentException("Invalid restored boundary");
        if (lastBatch != null && (!lastBatch.isEnded() || !lastBatch.getBuildingId().equals(buildingId)))
            throw new IllegalArgumentException("Invalid batch history");
        this.batch = batch;
        this.lastBatch = lastBatch;
        this.boundary = Objects.requireNonNull(boundary);
        this.automatic = automatic;
    }
}
