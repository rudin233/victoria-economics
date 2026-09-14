package com.bbmurloc.victoriaeconomics.server.building;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodGroupDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodGroupRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodRegistry;
import com.bbmurloc.victoriaeconomics.server.building.department.production.ProductionDepartment;

import java.util.Objects;
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
            BuildingTypeRegistry buildingTypeRegistry,
            ProductionMethodGroupRegistry productionMethodGroupRegistry,
            ProductionMethodRegistry productionMethodRegistry
    ) {
        this.buildingRegistry =
                Objects.requireNonNull(buildingRegistry);

        this.buildingRepository =
                Objects.requireNonNull(buildingRepository);

        this.buildingTypeRegistry =
                Objects.requireNonNull(buildingTypeRegistry);

        this.productionMethodGroupRegistry =
                Objects.requireNonNull(
                        productionMethodGroupRegistry
                );

        this.productionMethodRegistry =
                Objects.requireNonNull(
                        productionMethodRegistry
                );
    }

    /**
     * 创建 EconomicBuilding，
     * 并为每一个 PMG 设置默认 PM。
     */
    public EconomicBuilding createBuilding(
            String buildingTypeId
    ) {
        BuildingTypeDefinition buildingType =
                buildingTypeRegistry.get(
                        buildingTypeId
                );

        if (buildingType == null) {
            throw new IllegalArgumentException(
                    "Unknown building type: "
                            + buildingTypeId
            );
        }

        EconomicBuilding building =
                new EconomicBuilding(
                        UUID.randomUUID(),
                        buildingTypeId
                );

        ProductionDepartment productionDepartment =
                building.getProductionDepartment();

        for (String groupId
                : buildingType.productionMethodGroupIds()) {

            ProductionMethodGroupDefinition group =
                    productionMethodGroupRegistry.get(
                            groupId
                    );

            if (group == null) {
                throw new IllegalStateException(
                        "Unknown production method group: "
                                + groupId
                );
            }

            productionDepartment
                    .setSelectedProductionMethod(
                            groupId,
                            group.defaultMethodId()
                    );
        }

        buildingRepository.save(
                building
        );

        buildingRegistry.add(
                building
        );

        return building;
    }

    public boolean stopBuilding(
            UUID buildingId
    ) {
        EconomicBuilding building =
                buildingRegistry.get(
                        buildingId
                );

        if (building == null) {
            return false;
        }

        building.setStatus(
                BuildingStatus.STOPPED
        );

        buildingRepository.save(
                building
        );

        return true;
    }

    /**
     * 修改建筑当前设备数量。
     */
    public void setCurrentEquipment(
            UUID buildingId,
            int equipmentAmount
    ) {
        EconomicBuilding building =
                requireBuilding(
                        buildingId
                );

        BuildingTypeDefinition buildingType =
                requireBuildingType(
                        building
                );

        if (equipmentAmount < 0) {
            throw new IllegalArgumentException(
                    "Equipment amount cannot be negative"
            );
        }

        if (equipmentAmount
                > buildingType.maxEquipment()) {

            throw new IllegalArgumentException(
                    "Equipment amount exceeds max equipment: "
                            + equipmentAmount
                            + " > "
                            + buildingType.maxEquipment()
            );
        }

        building
                .getProductionDepartment()
                .setCurrentEquipment(
                        equipmentAmount
                );

        buildingRepository.save(
                building
        );
    }

    /**
     * 修改某个 PMG 当前选择的 PM。
     *
     * Victoria Economics 当前规则：
     *
     * later PM tier <= base PM tier
     *
     * 如果降低 base PM 后会导致已有 later PM 超过上限，
     * 则拒绝修改，不自动降级其他 PM。
     */
    public void selectProductionMethod(
            UUID buildingId,
            String groupId,
            String methodId
    ) {
        EconomicBuilding building =
                requireBuilding(
                        buildingId
                );

        BuildingTypeDefinition buildingType =
                requireBuildingType(
                        building
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
                productionMethodGroupRegistry.get(
                        groupId
                );

        if (group == null) {
            throw new IllegalArgumentException(
                    "Unknown production method group: "
                            + groupId
            );
        }

        ProductionMethodDefinition method =
                productionMethodRegistry.get(
                        methodId
                );

        if (method == null) {
            throw new IllegalArgumentException(
                    "Unknown production method: "
                            + methodId
            );
        }

        if (!group.methodIds().contains(methodId)
                || !method.groupId().equals(groupId)) {

            throw new IllegalArgumentException(
                    "Production method '"
                            + methodId
                            + "' does not belong to group '"
                            + groupId
                            + "'"
            );
        }

        ProductionDepartment productionDepartment =
                building.getProductionDepartment();

        String baseGroupId =
                buildingType
                        .baseProductionMethodGroupId();

        if (groupId.equals(baseGroupId)) {

            /*
             * 修改的是 Base PM。
             *
             * 新 Base tier 不能低于任何当前 later PM tier。
             */
            for (String otherGroupId
                    : buildingType
                    .productionMethodGroupIds()) {

                if (otherGroupId.equals(
                        baseGroupId
                )) {
                    continue;
                }

                String selectedMethodId =
                        productionDepartment
                                .getSelectedProductionMethodId(
                                        otherGroupId
                                );

                if (selectedMethodId == null) {
                    continue;
                }

                ProductionMethodDefinition
                        selectedMethod =
                        productionMethodRegistry.get(
                                selectedMethodId
                        );

                if (selectedMethod == null) {
                    throw new IllegalStateException(
                            "Unknown selected production method: "
                                    + selectedMethodId
                    );
                }

                if (selectedMethod.tier()
                        > method.tier()) {

                    throw new IllegalStateException(
                            "Cannot lower base production method tier to "
                                    + method.tier()
                                    + " because production method '"
                                    + selectedMethod.id()
                                    + "' currently uses tier "
                                    + selectedMethod.tier()
                    );
                }
            }

        } else {

            /*
             * 修改的是 later PM。
             *
             * 它的 tier 不能高于当前 Base PM tier。
             */
            String baseMethodId =
                    productionDepartment
                            .getSelectedProductionMethodId(
                                    baseGroupId
                            );

            if (baseMethodId == null) {
                throw new IllegalStateException(
                        "Building has no selected base production method"
                );
            }

            ProductionMethodDefinition baseMethod =
                    productionMethodRegistry.get(
                            baseMethodId
                    );

            if (baseMethod == null) {
                throw new IllegalStateException(
                        "Unknown selected base production method: "
                                + baseMethodId
                );
            }

            if (method.tier()
                    > baseMethod.tier()) {

                throw new IllegalStateException(
                        "Production method tier "
                                + method.tier()
                                + " exceeds current base production method tier "
                                + baseMethod.tier()
                );
            }
        }

        productionDepartment
                .setSelectedProductionMethod(
                        groupId,
                        methodId
                );

        buildingRepository.save(
                building
        );
    }

    private EconomicBuilding requireBuilding(
            UUID buildingId
    ) {
        EconomicBuilding building =
                buildingRegistry.get(
                        buildingId
                );

        if (building == null) {
            throw new IllegalArgumentException(
                    "Unknown building: "
                            + buildingId
            );
        }

        return building;
    }

    private BuildingTypeDefinition requireBuildingType(
            EconomicBuilding building
    ) {
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

        return buildingType;
    }
}