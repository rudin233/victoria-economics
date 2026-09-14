package com.bbmurloc.victoriaeconomics.server.production.calculation;

import java.util.Map;
import java.util.Objects;

public record ResolvedProductionRecipe(
        Map<String, Double> inputs,
        Map<String, Double> outputs,
        Map<String, Integer> requiredWorkers
) {

    public ResolvedProductionRecipe {
        inputs = Map.copyOf(Objects.requireNonNull(inputs, "inputs"));
        outputs = Map.copyOf(Objects.requireNonNull(outputs, "outputs"));
        requiredWorkers = Map.copyOf(
                Objects.requireNonNull(requiredWorkers, "requiredWorkers")
        );
    }
}
