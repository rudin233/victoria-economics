package com.bbmurloc.victoriaeconomics.common.definition.building;

import java.util.List;
import java.util.Objects;

public record BuildingTypeDefinition(
        String id,
        String industryId,
        String productionEquipmentTypeId,
        int maxProductionEquipment,
        List<String> productionMethodGroupIds
) {
    public BuildingTypeDefinition {

        Objects.requireNonNull(
                id,
                "id cannot be null"
        );

        Objects.requireNonNull(
                industryId,
                "industryId cannot be null"
        );

        Objects.requireNonNull(
                productionEquipmentTypeId,
                "productionEquipmentTypeId cannot be null"
        );

        Objects.requireNonNull(
                productionMethodGroupIds,
                "productionMethodGroupIds cannot be null"
        );

        if (maxProductionEquipment <= 0) {
            throw new IllegalArgumentException(
                    "maxProductionEquipment must be positive"
            );
        }

        productionMethodGroupIds =
                List.copyOf(
                        productionMethodGroupIds
                );

        if (productionMethodGroupIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Building type must have at least one production method group"
            );
        }
    }

    public String baseProductionMethodGroupId() {
        return productionMethodGroupIds.getFirst();
    }
}