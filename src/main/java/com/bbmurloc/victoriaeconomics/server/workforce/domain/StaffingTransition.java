package com.bbmurloc.victoriaeconomics.server.workforce.domain;

import java.util.*;

/** Pure proposal. Effective persistence must first accrue old wages in the same shared transaction. */
public record StaffingTransition(BuildingStaffing before, BuildingStaffing after) {
    public StaffingTransition {
        if (!before.buildingId().equals(after.buildingId()) || after.revision() != Math.incrementExact(before.revision()))
            throw new IllegalArgumentException("Invalid staffing transition identity/revision");
    }
    public static StaffingTransition forCapacity(BuildingStaffing before, Map<String, Integer> capacity) {
        var caps = BuildingStaffing.counts(capacity, true);
        var employees = new TreeMap<String, Integer>();
        var pending = new TreeMap<String, Integer>();
        before.employed().forEach((role, count) -> employees.put(role, Math.min(count, caps.getOrDefault(role, 0))));
        before.pending().forEach((role, target) -> pending.put(role, Math.min(target, caps.getOrDefault(role, 0))));
        return new StaffingTransition(before, new BuildingStaffing(before.buildingId(), employees, pending, Math.incrementExact(before.revision())));
    }
    public static StaffingTransition consumePending(BuildingStaffing before, Map<String, Integer> capacity, Map<String, Long> available) {
        available.forEach((role, count) -> { if (count == null || count < 0) throw new IllegalArgumentException("Invalid available supply"); });
        var capped = forCapacity(before, capacity).after();
        var next = new TreeMap<>(capped.employed());
        capped.pending().forEach((role, target) -> {
            int current = next.getOrDefault(role, 0);
            int result = target <= current ? target : Math.addExact(current,
                    Math.toIntExact(Math.min((long) Math.subtractExact(target, current), available.getOrDefault(role, 0L))));
            next.put(role, result);
        });
        return new StaffingTransition(before, new BuildingStaffing(before.buildingId(), next, Map.of(), Math.incrementExact(before.revision())));
    }
    public Map<String, Integer> hired() { return delta(false); }
    public Map<String, Integer> dismissed() { return delta(true); }
    private Map<String, Integer> delta(boolean dismissal) {
        var roles = new TreeSet<>(before.employed().keySet());
        roles.addAll(after.employed().keySet());
        var result = new TreeMap<String, Integer>();
        for (String role : roles) {
            int difference = Math.subtractExact(after.employed().getOrDefault(role, 0), before.employed().getOrDefault(role, 0));
            if (dismissal && difference < 0) result.put(role, Math.negateExact(difference));
            if (!dismissal && difference > 0) result.put(role, difference);
        }
        return Map.copyOf(result);
    }
}
