package com.bbmurloc.victoriaeconomics.server.production;

import java.util.Map;

public final class ResolvedProductionRecipe {

    private final Map<String, Double> inputs;
    private final Map<String, Double> outputs;
    private final Map<String, Integer> requiredWorkers;

    public ResolvedProductionRecipe(
            Map<String, Double> inputs,
            Map<String, Double> outputs,
            Map<String, Integer> requiredWorkers
    ) {
        this.inputs = Map.copyOf(inputs);
        this.outputs = Map.copyOf(outputs);
        this.requiredWorkers = Map.copyOf(requiredWorkers);
    }

    public Map<String, Double> getInputs() {
        return inputs;
    }

    public Map<String, Double> getOutputs() {
        return outputs;
    }

    public Map<String, Integer> getRequiredWorkers() {
        return requiredWorkers;
    }

    @Override
    public String toString() {
        return "ResolvedProductionRecipe{" +
                "inputs=" + inputs +
                ", outputs=" + outputs +
                ", requiredWorkers=" + requiredWorkers +
                '}';
    }
}