package com.bbmurloc.victoriaeconomics.server.production.domain;

import java.util.*;

/** Frozen settlement command and durable matching identity, including the recipe even for aborts. */
public record ProductionSettlement(UUID batchId, Outcome outcome, Map<String, Long> inputs, Map<String, Long> outputs) {
    public enum Outcome { COMPLETED, ABORTED }

    public ProductionSettlement {
        Objects.requireNonNull(batchId);
        Objects.requireNonNull(outcome);
        inputs = positiveAmounts(inputs);
        outputs = positiveAmounts(outputs);
        if (outputs.isEmpty()) throw new IllegalArgumentException("Settlement needs the frozen output recipe");
    }

    public static ProductionSettlement forBatch(ProductionBatch batch) {
        var outcome = switch (batch.getStatus()) {
            case SETTLING_COMPLETED, COMPLETED -> Outcome.COMPLETED;
            case SETTLING_ABORTED, ABORTED -> Outcome.ABORTED;
            default -> throw new IllegalStateException("Batch has no settlement decision");
        };
        var recipe = batch.getConfiguration().resolvedRecipe();
        return new ProductionSettlement(batch.getId(), outcome, recipe.inputs(), recipe.outputs());
    }

    public Map<String, Long> consumed() { return outcome == Outcome.COMPLETED ? inputs : Map.of(); }
    public Map<String, Long> produced() { return outcome == Outcome.COMPLETED ? outputs : Map.of(); }

    private static Map<String, Long> positiveAmounts(Map<String, Long> source) {
        source.forEach((good, amount) -> {
            if (good == null || good.isBlank() || amount == null || amount <= 0)
                throw new IllegalArgumentException("Frozen goods amounts must be positive: " + good);
        });
        return Map.copyOf(source);
    }
}
