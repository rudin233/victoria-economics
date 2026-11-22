package com.bbmurloc.victoriaeconomics.server.production.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable entity snapshot. Only ProductionExecution performs lifecycle transitions.
 */
public final class ProductionBatch {
    /** SETTLING records a decision awaiting the atomic resource transaction; COMPLETED and ABORTED are history. */
    public enum Status {ACTIVE, PAUSED, SETTLING_COMPLETED, SETTLING_ABORTED, COMPLETED, ABORTED}

    public static final int FULL_SPEED_TICKS = 200;
    private final UUID id;
    private final UUID buildingId;
    private final ProductionBatchConfiguration configuration;
    private final long progressUnits;
    private final Status status;
    private final long startedTick;
    private final Long endedTick;

    public ProductionBatch(UUID id, UUID buildingId, ProductionBatchConfiguration configuration, long progressUnits, Status status) {
        this(id, buildingId, configuration, progressUnits, status, 0, status == Status.COMPLETED || status == Status.ABORTED ? 0L : null);
    }

    public ProductionBatch(UUID id, UUID buildingId, ProductionBatchConfiguration configuration, long progressUnits,
                           Status status, long startedTick, Long endedTick) {
        this.id = Objects.requireNonNull(id);
        this.buildingId = Objects.requireNonNull(buildingId);
        this.configuration = Objects.requireNonNull(configuration);
        this.status = Objects.requireNonNull(status);
        long maximum = (long) FULL_SPEED_TICKS * configuration.workforcePlan().speedDenominator();
        if (progressUnits < 0 || progressUnits > maximum)
            throw new IllegalArgumentException("Invalid production progress");
        if ((status == Status.COMPLETED || status == Status.SETTLING_COMPLETED) && progressUnits != maximum) {
            throw new IllegalArgumentException("Completed production needs full progress");
        }
        if ((status == Status.ACTIVE || status == Status.PAUSED) && progressUnits == maximum) {
            throw new IllegalArgumentException("Full progress must enter settlement");
        }
        this.progressUnits = progressUnits;
        this.startedTick = startedTick;
        this.endedTick = endedTick;
        if (startedTick < 0 || isEnded() != (endedTick != null) || (endedTick != null && endedTick < startedTick))
            throw new IllegalArgumentException("Invalid batch lifetime");
    }

    public UUID getId() {
        return id;
    }

    public UUID getBuildingId() {
        return buildingId;
    }

    public ProductionBatchConfiguration getConfiguration() {
        return configuration;
    }

    public long getProgressUnits() {
        return progressUnits;
    }

    public long getMaximumProgressUnits() {
        return (long) FULL_SPEED_TICKS * configuration.workforcePlan().speedDenominator();
    }

    public double getProgress() {
        return (double) progressUnits / getMaximumProgressUnits();
    }

    public Status getStatus() {
        return status;
    }

    public long startedTick() { return startedTick; }

    public Long endedTick() { return endedTick; }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    public boolean isAborted() {
        return status == Status.ABORTED;
    }

    public boolean isEnded() {
        return isCompleted() || isAborted();
    }

    public boolean needsSettlement() {
        return status == Status.SETTLING_COMPLETED || status == Status.SETTLING_ABORTED;
    }
}
