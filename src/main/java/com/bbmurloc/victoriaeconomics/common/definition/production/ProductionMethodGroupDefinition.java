package com.bbmurloc.victoriaeconomics.common.definition.production;

import java.util.List;
import java.util.Objects;

public final class ProductionMethodGroupDefinition {

    private final String id;

    // 这个 PMG 属于哪一种建筑
    private final String buildingTypeId;

    // 按 tier/设计顺序排列
    private final List<String> methodIds;

    public ProductionMethodGroupDefinition(
            String id,
            String buildingTypeId,
            List<String> methodIds
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.buildingTypeId =
                Objects.requireNonNull(
                        buildingTypeId,
                        "buildingTypeId"
                );

        Objects.requireNonNull(methodIds, "methodIds");

        if (methodIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Production method group must contain at least one method"
            );
        }

        this.methodIds = List.copyOf(methodIds);
    }

    public String getId() {
        return id;
    }

    public String getBuildingTypeId() {
        return buildingTypeId;
    }

    public List<String> getMethodIds() {
        return methodIds;
    }

//    PMG 的第一个 PM 就是新建筑默认选择
    public String getDefaultMethodId() {
        return methodIds.getFirst();
    }
}