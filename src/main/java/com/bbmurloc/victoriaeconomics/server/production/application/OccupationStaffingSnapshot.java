package com.bbmurloc.victoriaeconomics.server.production.application;

import java.util.Objects;

/** Legacy HR target and formal employment diagnostics, without a dedicated asset capacity. */
public record OccupationStaffingSnapshot(String occupationId, int requiredWorkers, double targetRatio,
                                        int targetWorkers, int hiringTargetWorkers,
                                        int employedWorkers, int effectiveWorkers, double staffingRatio) {
    public OccupationStaffingSnapshot {
        Objects.requireNonNull(occupationId);
        if (requiredWorkers <= 0) throw new IllegalArgumentException("Worker demand must be positive");
        if (!Double.isFinite(targetRatio) || targetRatio < 0 || targetRatio > 1)
            throw new IllegalArgumentException("Target ratio must be between 0 and 1");
        if (targetWorkers < 0 || hiringTargetWorkers < 0 || employedWorkers < 0 || effectiveWorkers < 0)
            throw new IllegalArgumentException("Worker counts cannot be negative");
        if (!Double.isFinite(staffingRatio) || staffingRatio < 0 || staffingRatio > 1)
            throw new IllegalArgumentException("Staffing ratio must be between 0 and 1");
    }

    public int hiringShortfall() { return Math.max(0, hiringTargetWorkers - employedWorkers); }
    public int workersAboveHiringTarget() { return Math.max(0, employedWorkers - hiringTargetWorkers); }
}
