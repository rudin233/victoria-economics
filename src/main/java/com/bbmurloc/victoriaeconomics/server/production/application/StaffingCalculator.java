package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.workforce.port.StaffingQueryPort;
import java.util.*;

/** Staged integer staffing diagnostics only. ProductionService still uses WorkforcePort/NPC facts. */
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
            Integer pending = facts.staffing().pending().get(role);
            occupations.put(role, new OccupationStaffingSnapshot(role, facts.capacity().getOrDefault(role, 0),
                    facts.staffing().employed().getOrDefault(role, 0), pending == null ? OptionalInt.empty() : OptionalInt.of(pending),
                    supply.total(), supply.occupied(), available));
        }
        return new StaffingSnapshot(building, facts.staffing().revision(), facts.effectivePmRevision(), occupations);
    }
}
