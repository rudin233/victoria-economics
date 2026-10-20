package com.bbmurloc.victoriaeconomics.server.production.domain;

import java.util.Map;
import java.util.Objects;

public record ResolvedProductionRecipe(
        Map<String, Long> inputs,
        Map<String, Long> outputs,
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
        if (requiredWorkers.isEmpty()) throw new IllegalArgumentException("A goods-producing recipe needs at least one positive occupation demand");
    }

    private static void requireQuantity(String good, long quantity) {
        if (good == null || good.isBlank() || quantity <= 0)
            throw new IllegalArgumentException("Recipe quantity must be a positive integer: " + good);
    }
}
