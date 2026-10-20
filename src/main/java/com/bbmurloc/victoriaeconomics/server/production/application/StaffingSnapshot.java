package com.bbmurloc.victoriaeconomics.server.production.application;

import java.util.*;

/** Independent staged staffing state, not the effective employment source of a running batch. */
public record StaffingSnapshot(UUID buildingId, long staffingRevision, long effectivePmRevision,
                               Map<String, OccupationStaffingSnapshot> occupations) {
    public StaffingSnapshot { occupations = Map.copyOf(occupations); }
}
