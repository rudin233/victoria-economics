package com.bbmurloc.victoriaeconomics.common.definition.production;

import java.util.Map;
import java.util.Objects;

public final class ProductionMethodDefinition {

    private final String id;

    // 属于哪个 PMG
    private final String groupId;

    // Victoria Economics 自己使用的等级
    private final int tier;

    // 对最终配方的变化量
    private final Map<String, Double> inputChanges;
    private final Map<String, Double> outputChanges;
    private final Map<String, Integer> workerChanges;

    public ProductionMethodDefinition(
            String id,
            String groupId,
            int tier,
            Map<String, Double> inputChanges,
            Map<String, Double> outputChanges,
            Map<String, Integer> workerChanges
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.groupId = Objects.requireNonNull(groupId, "groupId");

        if (tier <= 0) {
            throw new IllegalArgumentException(
                    "Production method tier must be greater than 0"
            );
        }

        this.tier = tier;

        this.inputChanges = Map.copyOf(
                Objects.requireNonNull(inputChanges, "inputChanges")
        );

        this.outputChanges = Map.copyOf(
                Objects.requireNonNull(outputChanges, "outputChanges")
        );

        this.workerChanges = Map.copyOf(
                Objects.requireNonNull(workerChanges, "workerChanges")
        );
    }

    public String getId() {
        return id;
    }

    public String getGroupId() {
        return groupId;
    }

    public int getTier() {
        return tier;
    }

    public Map<String, Double> getInputChanges() {
        return inputChanges;
    }

    public Map<String, Double> getOutputChanges() {
        return outputChanges;
    }

    public Map<String, Integer> getWorkerChanges() {
        return workerChanges;
    }
}