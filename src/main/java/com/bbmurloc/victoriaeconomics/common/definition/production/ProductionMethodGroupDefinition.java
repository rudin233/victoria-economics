package com.bbmurloc.victoriaeconomics.common.definition.production;

import java.util.List;
import java.util.Objects;

public record ProductionMethodGroupDefinition(
        String id,
        // 这个 PMG 属于哪一种建筑
        String buildingTypeId,
        // 按 tier/设计顺序排列
        List<String> methodIds
) {

    public ProductionMethodGroupDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(buildingTypeId, "buildingTypeId");
        Objects.requireNonNull(methodIds, "methodIds");

        if (methodIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Production method group must contain at least one method"
            );
        }

        methodIds = List.copyOf(methodIds);
    }

    // PMG 的第一个 PM 就是新建筑默认选择
    public String defaultMethodId() {
        return methodIds.getFirst();
    }
}
