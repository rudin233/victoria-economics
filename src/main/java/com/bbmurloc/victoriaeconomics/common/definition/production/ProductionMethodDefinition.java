package com.bbmurloc.victoriaeconomics.common.definition.production;

import java.util.Map;
import java.util.Objects;

public record ProductionMethodDefinition(
        String id,
        // 属于哪个 PMG
        String groupId,
        // Victoria Economics 自己使用的等级
        int tier,
        // 对最终配方的变化量
        Map<String, Double> inputChanges,
        Map<String, Double> outputChanges,
        Map<String, Integer> workerChanges
) {

    public ProductionMethodDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");

        if (tier <= 0) {
            throw new IllegalArgumentException(
                    "Production method tier must be greater than 0"
            );
        }

        inputChanges = Map.copyOf(
                Objects.requireNonNull(inputChanges, "inputChanges")
        );
        outputChanges = Map.copyOf(
                Objects.requireNonNull(outputChanges, "outputChanges")
        );
        workerChanges = Map.copyOf(
                Objects.requireNonNull(workerChanges, "workerChanges")
        );
    }
}
