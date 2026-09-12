package com.bbmurloc.victoriaeconomics.common.definition.building;

import java.util.List;
import java.util.Objects;

public record BuildingTypeDefinition(
        String id,
        String industryId,
        String equipmentTypeId,
        int maxEquipment,
        List<String> productionMethodGroupIds
) {

    public BuildingTypeDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(industryId, "industryId");
        Objects.requireNonNull(equipmentTypeId, "equipmentTypeId");

        if (maxEquipment <= 0) {
            throw new IllegalArgumentException(
                    "maxEquipment must be greater than 0"
            );
        }

        Objects.requireNonNull(productionMethodGroupIds, "productionMethodGroupIds");

        if (productionMethodGroupIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Building type must contain at least one production method group"
            );
        }

        productionMethodGroupIds = List.copyOf(productionMethodGroupIds);
    }

    public String baseProductionMethodGroupId() {
        return productionMethodGroupIds.getFirst();
    }
}
