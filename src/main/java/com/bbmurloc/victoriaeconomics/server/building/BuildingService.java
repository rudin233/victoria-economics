package com.bbmurloc.victoriaeconomics.server.building;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodGroupDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodGroupRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodRegistry;

import java.util.UUID;

public final class BuildingService {

    private final BuildingRegistry buildingRegistry;
    private final BuildingRepository buildingRepository;

    private final BuildingTypeRegistry buildingTypeRegistry;
    private final ProductionMethodGroupRegistry productionMethodGroupRegistry;
    private final ProductionMethodRegistry productionMethodRegistry;

    public BuildingService(
            BuildingRegistry buildingRegistry,
            BuildingRepository buildingRepository,
            BuildingTypeRegistry buildingTypeRegistry, ProductionMethodGroupRegistry productionMethodGroupRegistry, ProductionMethodRegistry productionMethodRegistry
    ) {
        this.buildingRegistry = buildingRegistry;
        this.buildingRepository = buildingRepository;
        this.buildingTypeRegistry = buildingTypeRegistry;
        this.productionMethodGroupRegistry = productionMethodGroupRegistry;
        this.productionMethodRegistry = productionMethodRegistry;
    }

    public EconomicBuilding createBuilding(
            String buildingTypeId
    ) {
        BuildingTypeDefinition buildingType =
                buildingTypeRegistry.get(buildingTypeId);

        if (buildingType == null) {
            throw new IllegalArgumentException(
                    "Unknown building type: " + buildingTypeId
            );
        }

        UUID buildingId = UUID.randomUUID();

        EconomicBuilding building =
                new EconomicBuilding(
                        buildingId,
                        buildingTypeId
                );

        for (String groupId
                : buildingType.productionMethodGroupIds()) {

            ProductionMethodGroupDefinition group =
                    productionMethodGroupRegistry.get(groupId);

            if (group == null) {
                throw new IllegalStateException(
                        "Unknown production method group: "
                                + groupId
                );
            }

            building.setSelectedProductionMethod(
                    groupId,
                    group.defaultMethodId()
            );
        }

        buildingRepository.save(building);
        buildingRegistry.add(building);

        return building;
    }

    public boolean stopBuilding(UUID buildingId) {
        EconomicBuilding building =
                buildingRegistry.get(buildingId);

        if (building == null) {
            return false;
        }

        building.setStatus(BuildingStatus.STOPPED);

        buildingRepository.save(building);

        return true;
    }

    public void selectProductionMethod(
            UUID buildingId,
            String groupId,
            String methodId
    ) {
        EconomicBuilding building =
                buildingRegistry.get(buildingId);

        if (building == null) {
            throw new IllegalArgumentException(
                    "Unknown building: " + buildingId
            );
        }

        BuildingTypeDefinition buildingType =
                buildingTypeRegistry.get(
                        building.getBuildingTypeId()
                );

        if (!buildingType
                .productionMethodGroupIds()
                .contains(groupId)) {
            throw new IllegalArgumentException(
                    "Production method group '"
                            + groupId
                            + "' does not belong to building type '"
                            + buildingType.id()
                            + "'"
            );
        }

        ProductionMethodGroupDefinition group =
                productionMethodGroupRegistry.get(groupId);

        ProductionMethodDefinition targetMethod =
                productionMethodRegistry.get(methodId);

        if (targetMethod == null) {
            throw new IllegalArgumentException(
                    "Unknown production method: " + methodId
            );
        }

        if (!targetMethod.groupId().equals(groupId)) {
            throw new IllegalArgumentException(
                    "Production method '"
                            + methodId
                            + "' does not belong to group '"
                            + groupId
                            + "'"
            );
        }

        String baseGroupId =
                buildingType.baseProductionMethodGroupId();

        // 正在修改第一个/基础 PMG
        if (groupId.equals(baseGroupId)) {

            int targetBaseTier =
                    targetMethod.tier();

            for (String otherGroupId
                    : buildingType.productionMethodGroupIds()) {

                if (otherGroupId.equals(baseGroupId)) {
                    continue;
                }

                String selectedMethodId =
                        building.getSelectedProductionMethodId(
                                otherGroupId
                        );

                ProductionMethodDefinition selectedMethod =
                        productionMethodRegistry.get(
                                selectedMethodId
                        );

                if (selectedMethod.tier() > targetBaseTier) {
                    throw new IllegalStateException(
                            "Cannot lower base production method to tier "
                                    + targetBaseTier
                                    + " because group '"
                                    + otherGroupId
                                    + "' is using tier "
                                    + selectedMethod.tier()
                    );
                }
            }
        }

        // 正在修改后续 PMG
        else {

            String selectedBaseMethodId =
                    building.getSelectedProductionMethodId(
                            baseGroupId
                    );

            ProductionMethodDefinition selectedBaseMethod =
                    productionMethodRegistry.get(
                            selectedBaseMethodId
                    );

            if (targetMethod.tier()
                    > selectedBaseMethod.tier()) {

                throw new IllegalStateException(
                        "Production method tier "
                                + targetMethod.tier()
                                + " exceeds base production method tier "
                                + selectedBaseMethod.tier()
                );
            }
        }

        building.setSelectedProductionMethod(
                groupId,
                methodId
        );

        buildingRepository.save(building);
    }

    public void setCurrentEquipment(
            UUID buildingId,
            int equipmentAmount
    ) {
        EconomicBuilding building =
                buildingRegistry.get(buildingId);

        if (building == null) {
            throw new IllegalArgumentException(
                    "Unknown building: " + buildingId
            );
        }

        BuildingTypeDefinition buildingType =
                buildingTypeRegistry.get(
                        building.getBuildingTypeId()
                );

        if (buildingType == null) {
            throw new IllegalStateException(
                    "Unknown building type: "
                            + building.getBuildingTypeId()
            );
        }

        if (equipmentAmount < 0) {
            throw new IllegalArgumentException(
                    "Equipment amount cannot be negative"
            );
        }

        if (equipmentAmount > buildingType.maxEquipment()) {
            throw new IllegalArgumentException(
                    "Equipment amount exceeds max equipment: "
                            + equipmentAmount
                            + " > "
                            + buildingType.maxEquipment()
            );
        }

        building.setCurrentEquipment(equipmentAmount);

        buildingRepository.save(building);
    }
}
