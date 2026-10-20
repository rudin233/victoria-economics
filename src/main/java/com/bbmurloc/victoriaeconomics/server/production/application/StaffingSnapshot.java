package com.bbmurloc.victoriaeconomics.server.production.application;

import java.util.*;

/** Current formal Staffing query. Running Batch participation remains an immutable historical snapshot. */
public record StaffingSnapshot(UUID buildingId, long staffingRevision, long effectivePmRevision,
                               Map<String, OccupationStaffingSnapshot> occupations) {
    public StaffingSnapshot { occupations = Map.copyOf(occupations); }
}
