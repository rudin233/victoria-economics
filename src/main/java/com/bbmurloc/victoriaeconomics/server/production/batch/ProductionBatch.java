package com.bbmurloc.victoriaeconomics.server.production.batch;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable entity snapshot. Only ProductionExecution performs lifecycle transitions.
 */
public final class ProductionBatch {
    public enum Status {ACTIVE, PAUSED, SETTLING_COMPLETED, SETTLING_ABORTED, COMPLETED, ABORTED}

    public static final int FULL_SPEED_TICKS = 1200;
    private final UUID id;
    private final UUID buildingId;
    private final ProductionBatchConfiguration configuration;
    private final long progressUnits;
    private final Status status;

    public ProductionBatch(UUID id, UUID buildingId, ProductionBatchConfiguration configuration, long progressUnits, Status status) {
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
