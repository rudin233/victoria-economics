package com.bbmurloc.victoriaeconomics.server.workforce.staffing;

import java.util.*;

public record WorkforcePlan(Map<String, List<UUID>> employees, int speedNumerator, int speedDenominator) {
    public WorkforcePlan {
        Map<String, List<UUID>> copy = new TreeMap<>();
        Set<UUID> unique = new HashSet<>();
        employees.forEach((occupation, ids) -> {
            copy.put(occupation, List.copyOf(ids));
            for (UUID id : ids)
                if (!unique.add(id)) throw new IllegalArgumentException("Employee selected twice: " + id);
        });
        employees = Map.copyOf(copy);
        if (speedNumerator <= 0 || speedDenominator <= 0 || speedNumerator > speedDenominator)
            throw new IllegalArgumentException("Invalid workforce speed");
    }

    public double speed() {
        return (double) speedNumerator / speedDenominator;
    }
}
