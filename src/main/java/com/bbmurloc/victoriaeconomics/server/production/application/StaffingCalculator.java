package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.workforce.port.StaffingQueryPort;
import java.util.*;

/** 从同一快照显示本批占用、配方需求及区域供给。 */
public final class StaffingCalculator {
    private final StaffingQueryPort query;
    public StaffingCalculator(StaffingQueryPort query) { this.query = Objects.requireNonNull(query); }
    public StaffingSnapshot calculate(UUID building) {
        var facts = query.read(building);
        var occupations = new TreeMap<String, OccupationStaffingSnapshot>();
        for (var entry : facts.supply().entrySet()) {
            String role = entry.getKey();
            var supply = entry.getValue();
            OptionalLong available = supply.total().isPresent()
                    ? OptionalLong.of(Math.subtractExact(supply.total().getAsLong(), supply.occupied())) : OptionalLong.empty();
            occupations.put(role, new OccupationStaffingSnapshot(role, facts.capacity().getOrDefault(role, 0),
                    facts.staffing().occupiedByOccupation().getOrDefault(role, 0),
                    supply.total(), supply.occupied(), available));
        }
        return new StaffingSnapshot(building, facts.staffing().batchId(), facts.staffing().revision(), facts.effectivePmRevision(), occupations);
    }
}
