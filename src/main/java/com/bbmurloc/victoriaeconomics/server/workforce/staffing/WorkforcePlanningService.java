package com.bbmurloc.victoriaeconomics.server.workforce.staffing;

import com.bbmurloc.victoriaeconomics.server.workforce.employment.EmploymentRecord;

import java.util.*;
import java.util.function.BiPredicate;

/**
 * Stateless calculation using formal employment occupation, never substitute qualifications.
 */
public final class WorkforcePlanningService {
    public WorkforcePlan plan(UUID building, Map<String, Integer> demand, int installed, int capacity,
                              Collection<EmploymentRecord> employment, BiPredicate<UUID, String> qualified) {
        if (capacity <= 0 || installed < 0 || installed > capacity)
            throw new IllegalArgumentException("Invalid equipment capability");
        int numerator = 1;
        int denominator = 1;
        Map<String, List<UUID>> candidates = new TreeMap<>();
        for (var entry : new TreeMap<>(demand).entrySet()) {
            String occupation = entry.getKey();
            int required = entry.getValue();
            if (required <= 0) throw new IllegalArgumentException("Worker demand must be positive");
            List<UUID> eligible = employment.stream()
                    .filter(e -> e.buildingId().equals(building) && e.occupationId().equals(occupation))
                    .filter(e -> qualified.test(e.employeeId(), occupation))
                    .map(EmploymentRecord::employeeId).distinct().sorted().toList();
            int available = Math.min(eligible.size(), (int) ((long) installed * required / capacity));
            if ((long) available * 10 < required)
                throw new IllegalStateException("Minimum 10% staffing not met: " + occupation);
            candidates.put(occupation, eligible.subList(0, available));
            if ((long) available * denominator < (long) numerator * required) {
                numerator = available;
                denominator = required;
            }
        }
        Map<String, List<UUID>> selected = new TreeMap<>();
        for (var entry : candidates.entrySet()) {
            long product = (long) numerator * demand.get(entry.getKey());
            int minimum = (int) ((product + denominator - 1) / denominator);
            selected.put(entry.getKey(), List.copyOf(entry.getValue().subList(0, minimum)));
        }
        return new WorkforcePlan(selected, numerator, denominator);
    }
}
