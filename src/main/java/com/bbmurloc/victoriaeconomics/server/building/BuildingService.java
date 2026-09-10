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
                : buildingType.getProductionMethodGroupIds()) {

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
                    group.getDefaultMethodId()
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
                .getProductionMethodGroupIds()
                .contains(groupId)) {
            throw new IllegalArgumentException(
                    "Production method group '"
                            + groupId
                            + "' does not belong to building type '"
                            + buildingType.getId()
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

        if (!targetMethod.getGroupId().equals(groupId)) {
            throw new IllegalArgumentException(
                    "Production method '"
                            + methodId
                            + "' does not belong to group '"
                            + groupId
                            + "'"
            );
        }

        String baseGroupId =
                buildingType.getBaseProductionMethodGroupId();

        // 正在修改第一个/基础 PMG
        if (groupId.equals(baseGroupId)) {

            int targetBaseTier =
                    targetMethod.getTier();

            for (String otherGroupId
                    : buildingType.getProductionMethodGroupIds()) {

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

                if (selectedMethod.getTier() > targetBaseTier) {
                    throw new IllegalStateException(
                            "Cannot lower base production method to tier "
                                    + targetBaseTier
                                    + " because group '"
                                    + otherGroupId
                                    + "' is using tier "
                                    + selectedMethod.getTier()
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

            if (targetMethod.getTier()
                    > selectedBaseMethod.getTier()) {

                throw new IllegalStateException(
                        "Production method tier "
                                + targetMethod.getTier()
                                + " exceeds base production method tier "
                                + selectedBaseMethod.getTier()
                );
            }
        }

        building.setSelectedProductionMethod(
                groupId,
                methodId
        );

        buildingRepository.save(building);
    }
}