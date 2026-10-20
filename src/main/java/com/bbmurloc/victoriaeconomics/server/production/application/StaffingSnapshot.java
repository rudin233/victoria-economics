package com.bbmurloc.victoriaeconomics.server.production.application;

import java.util.Map;

/** Read-only staffing diagnostics; no authority to start a batch or bypass qualification checks. */
public record StaffingSnapshot(Map<String, OccupationStaffingSnapshot> occupations,
                               boolean canStart, double productionSpeed) {
    public StaffingSnapshot {
        occupations = Map.copyOf(occupations);
        if (!Double.isFinite(productionSpeed) || productionSpeed < 0 || productionSpeed > 1)
            throw new IllegalArgumentException("Production speed must be between 0 and 1");
    }
}
