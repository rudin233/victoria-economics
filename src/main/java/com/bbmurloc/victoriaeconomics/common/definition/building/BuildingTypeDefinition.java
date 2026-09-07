package com.bbmurloc.victoriaeconomics.common.definition.building;

import java.util.Objects;

public final class BuildingTypeDefinition {

    private final String id;
    private final String equipmentTypeId;
    private final int maxEquipment;

    public BuildingTypeDefinition(
            String id,
            String equipmentTypeId,
            int maxEquipment
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.equipmentTypeId =
                Objects.requireNonNull(
                        equipmentTypeId,
                        "equipmentTypeId"
                );

        if (maxEquipment <= 0) {
            throw new IllegalArgumentException(
                    "maxEquipment must be greater than 0"
            );
        }

        this.maxEquipment = maxEquipment;
    }

    public String getId() {
        return id;
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
}