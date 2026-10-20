package com.bbmurloc.victoriaeconomics.server.workforce.domain;

import java.util.*;

/** Independent staged staffing root. Never contains NPCs, PM capacity, supply or payroll. */
public record BuildingStaffing(UUID buildingId, Map<String, Integer> employed,
                               Map<String, Integer> pending, long revision) {
    public BuildingStaffing {
        Objects.requireNonNull(buildingId);
        if (revision < 0) throw new IllegalArgumentException("Negative staffing revision");
        employed = counts(employed, true);
        pending = counts(pending, false);
    }
    public static BuildingStaffing empty(UUID id) { return new BuildingStaffing(id, Map.of(), Map.of(), 0); }
    public BuildingStaffing requestTarget(String occupation, int target, Map<String, Integer> capacity) {
        if (occupation == null || occupation.isBlank() || target < 0 || target > capacity.getOrDefault(occupation, 0))
            throw new IllegalArgumentException("Target exceeds effective PM position capacity");
        if (!capacity.containsKey(occupation)) throw new IllegalArgumentException("Occupation is absent from effective PM");
        if (Objects.equals(pending.get(occupation), target)) return this;
        var next = new TreeMap<>(pending);
        next.put(occupation, target);
        return new BuildingStaffing(buildingId, employed, next, Math.incrementExact(revision));
    }
    public BuildingStaffing cancel(String occupation) {
        if (!pending.containsKey(occupation)) return this;
        var next = new TreeMap<>(pending);
        next.remove(occupation);
        return new BuildingStaffing(buildingId, employed, next, Math.incrementExact(revision));
    }
    public BuildingStaffing apply(StaffingTransition transition) {
        if (!this.equals(transition.before())) throw new IllegalArgumentException("Stale or foreign staffing transition");
        return transition.after();
    }
    static Map<String, Integer> counts(Map<String, Integer> source, boolean omitZero) {
        var result = new TreeMap<String, Integer>();
        source.forEach((role, count) -> {
            if (role == null || role.isBlank() || count == null || count < 0) throw new IllegalArgumentException("Invalid occupation count");
            if (!omitZero || count != 0) result.put(role, count);
        });
        return Map.copyOf(result);
    }
}
