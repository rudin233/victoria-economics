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
        inputs.forEach((good, q) -> requireQuantity(good, q));
        outputs.forEach((good, q) -> requireQuantity(good, q));
        requiredWorkers.forEach((occupation, count) -> {
            if (count <= 0) throw new IllegalArgumentException("Worker demand must be positive: " + occupation);
        });
        if (outputs.isEmpty()) throw new IllegalArgumentException("Recipe needs at least one output");
    }

    private static void requireQuantity(String good, double quantity) {
        if (!Double.isFinite(quantity) || quantity <= 0)
            throw new IllegalArgumentException("Recipe quantity must be finite and positive: " + good);
    }
}
