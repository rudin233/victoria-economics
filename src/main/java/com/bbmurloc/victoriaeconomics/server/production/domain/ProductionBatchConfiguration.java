package com.bbmurloc.victoriaeconomics.server.production.domain;

import java.util.*;

/**
 * Every production commitment remains immutable until settlement completes.
 */
public record ProductionBatchConfiguration(
        Map<String, String> productionMethodSelections,
        ResolvedProductionRecipe resolvedRecipe,
        WorkforcePlan workforcePlan, long effectiveRevision) {
    public ProductionBatchConfiguration(Map<String, String> selections, ResolvedProductionRecipe recipe,
                                        WorkforcePlan plan) {
        this(selections, recipe, plan, 0);
    }
    public ProductionBatchConfiguration {
        if (effectiveRevision < 0) throw new IllegalArgumentException("Negative effective revision");
        productionMethodSelections = Map.copyOf(productionMethodSelections);
        Objects.requireNonNull(resolvedRecipe);
        Objects.requireNonNull(workforcePlan);
        if (!resolvedRecipe.requiredWorkers().keySet().equals(workforcePlan.employees().keySet())) {
            throw new IllegalArgumentException("Workforce does not match recipe occupations");
        }
        int numerator = 1, denominator = 1;
        for (var entry : resolvedRecipe.requiredWorkers().entrySet()) {
            int required = entry.getValue();
            int selected = workforcePlan.employees().get(entry.getKey()).size();
            if ((long) selected * 10 < required || selected > required) {
                throw new IllegalArgumentException("Invalid participation: " + entry.getKey());
            }
            if ((long) selected * denominator < (long) numerator * required) {
                numerator = selected;
                denominator = required;
            }
        }
        if ((long) numerator * workforcePlan.speedDenominator() != (long) denominator * workforcePlan.speedNumerator()) {
            throw new IllegalArgumentException("Workforce speed does not match participants");
        }
    }

    public double productionSpeed() {
        return workforcePlan.speed();
    }

    public Map<String, List<UUID>> activeEmployeesByOccupation() {
        return workforcePlan.employees();
    }
}
