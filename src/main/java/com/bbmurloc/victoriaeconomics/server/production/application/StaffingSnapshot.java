package com.bbmurloc.victoriaeconomics.server.production.application;

import java.util.*;

/** 当前批次占用和区域供给查询结果。 */
public record StaffingSnapshot(UUID buildingId, UUID batchId, long staffingRevision, long effectivePmRevision,
                               Map<String, OccupationStaffingSnapshot> occupations) {
    public StaffingSnapshot { occupations = Map.copyOf(occupations); }
}
