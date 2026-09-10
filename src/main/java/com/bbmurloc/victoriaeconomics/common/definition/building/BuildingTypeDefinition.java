package com.bbmurloc.victoriaeconomics.common.definition.building;

import java.util.List;
import java.util.Objects;

public final class BuildingTypeDefinition {

    private final String id;
    private final String industryId;
    private final String equipmentTypeId;
    private final int maxEquipment;

    private final List<String> productionMethodGroupIds;

    public BuildingTypeDefinition(
            String id,
            String industryId,
            String equipmentTypeId,
            int maxEquipment,
            List<String> productionMethodGroupIds
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.industryId = Objects.requireNonNull(
                industryId,
                "industryId"
        );
        this.equipmentTypeId = Objects.requireNonNull(
                equipmentTypeId,
                "equipmentTypeId"
        );

        if (maxEquipment <= 0) {
            throw new IllegalArgumentException(
                    "maxEquipment must be greater than 0"
            );
        }

        Objects.requireNonNull(
                productionMethodGroupIds,
                "productionMethodGroupIds"
        );

        if (productionMethodGroupIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Building type must contain at least one production method group"
            );
        }

        this.maxEquipment = maxEquipment;
        this.productionMethodGroupIds =
                List.copyOf(productionMethodGroupIds);
    }

    public String getId() {
        return id;
    }

    public String getIndustryId() {
        return industryId;
    }

    public String getEquipmentTypeId() {
        return equipmentTypeId;
    }

    public int getMaxEquipment() {
        return maxEquipment;
    }

    @Override
    public String toString() {
        return "BuildingTypeDefinition{" +
                "id='" + id + '\'' +
                ", equipmentTypeId='" + equipmentTypeId + '\'' +
                ", maxEquipment=" + maxEquipment +
                '}';
    }

    public List<String> getProductionMethodGroupIds() {
        return productionMethodGroupIds;
    }

    public String getBaseProductionMethodGroupId() {
        return productionMethodGroupIds.getFirst();
    }



}