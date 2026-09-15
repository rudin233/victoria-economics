package com.bbmurloc.victoriaeconomics.common.definition;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.good.GoodRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.industry.IndustryRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.occupation.OccupationRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodGroupDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodGroupRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.productionequipment.ProductionEquipmentDefinitionRegistry;

import java.util.HashMap;
import java.util.Map;

public final class DefinitionValidator {

    private DefinitionValidator() {
    }

    public static void validateAll(
            IndustryRegistry industryRegistry,
            GoodRegistry goodRegistry,
            OccupationRegistry occupationRegistry,
            ProductionEquipmentDefinitionRegistry productionEquipmentDefinitionRegistry,
            BuildingTypeRegistry buildingTypeRegistry,
            ProductionMethodGroupRegistry productionMethodGroupRegistry,
            ProductionMethodRegistry productionMethodRegistry


    ) {
        validateBuildingTypes(
                industryRegistry,
                productionEquipmentDefinitionRegistry,
                buildingTypeRegistry,
                productionMethodGroupRegistry
        );

        validateProductionMethodGroups(
                buildingTypeRegistry,
                productionMethodGroupRegistry,
                productionMethodRegistry
        );

        validateProductionMethods(
                goodRegistry,
                occupationRegistry,
                productionMethodGroupRegistry,
                productionMethodRegistry
        );
    }

    /**
     * 检查：
     *
     * 1. BuildingType 引用的 Industry 是否存在
     * 2. BuildingType 引用的所有 PMG 是否存在
     * 3. PMG 是否确实属于这个 BuildingType
     */
    private static void validateBuildingTypes(
            IndustryRegistry industryRegistry,
            ProductionEquipmentDefinitionRegistry productionEquipmentDefinitionRegistry,
            BuildingTypeRegistry buildingTypeRegistry,
            ProductionMethodGroupRegistry productionMethodGroupRegistry
    ) {
        Map<String, String> equipmentTypeOwners =
                new HashMap<>();

        for (BuildingTypeDefinition buildingType
                : buildingTypeRegistry.getAll()) {

            if (!industryRegistry.contains(
                    buildingType.industryId()
            )) {
                throw new IllegalStateException(
                        "Building type '"
                                + buildingType.id()
                                + "' references unknown industry '"
                                + buildingType.industryId()
                                + "'"
                );
            }

            if (!productionEquipmentDefinitionRegistry.contains(
                    buildingType.productionEquipmentTypeId()
            )) {
                throw new IllegalStateException(
                        "Building type '"
                                + buildingType.id()
                                + "' references unknown production equipment type '"
                                + buildingType.productionEquipmentTypeId()
                                + "'"
                );
            }

            String previousBuildingTypeId =
                    equipmentTypeOwners.putIfAbsent(
                            buildingType.productionEquipmentTypeId(),
                            buildingType.id()
                    );

            if (previousBuildingTypeId != null) {
                throw new IllegalStateException(
                        "Production equipment type '"
                                + buildingType.productionEquipmentTypeId()
                                + "' is referenced by both building type '"
                                + previousBuildingTypeId
                                + "' and building type '"
                                + buildingType.id()
                                + "'"
                );
            }

            /*
             * 下面继续保留你原来的 PMG 验证。
             */
            for (String groupId
                    : buildingType.productionMethodGroupIds()) {

                ProductionMethodGroupDefinition group =
                        productionMethodGroupRegistry.get(
                                groupId
                        );

                if (group == null) {
                    throw new IllegalStateException(
                            "Building type '"
                                    + buildingType.id()
                                    + "' references unknown production method group '"
                                    + groupId
                                    + "'"
                    );
                }

                if (!group.buildingTypeId()
                        .equals(buildingType.id())) {

                    throw new IllegalStateException(
                            "Production method group '"
                                    + groupId
                                    + "' belongs to building type '"
                                    + group.buildingTypeId()
                                    + "', but is referenced by building type '"
                                    + buildingType.id()
                                    + "'"
                    );
                }
            }
        }
    }

    /**
     * 检查：
     *
     * 1. PMG 声明的 BuildingType 是否存在
     * 2. PMG 是否真的被这个 BuildingType 引用
     * 3. PMG 里面声明的每个 PM 是否存在
     * 4. PM 是否真的属于这个 PMG
     */
    private static void validateProductionMethodGroups(
            BuildingTypeRegistry buildingTypeRegistry,
            ProductionMethodGroupRegistry productionMethodGroupRegistry,
            ProductionMethodRegistry productionMethodRegistry
    ) {
        for (ProductionMethodGroupDefinition group
                : productionMethodGroupRegistry.getAll()) {

            BuildingTypeDefinition buildingType =
                    buildingTypeRegistry.get(
                            group.buildingTypeId()
                    );

            if (buildingType == null) {
                throw new IllegalStateException(
                        "Production method group '"
                                + group.id()
                                + "' references unknown building type '"
                                + group.buildingTypeId()
                                + "'"
                );
            }

            if (!buildingType
                    .productionMethodGroupIds()
                    .contains(group.id())) {

                throw new IllegalStateException(
                        "Production method group '"
                                + group.id()
                                + "' belongs to building type '"
                                + buildingType.id()
                                + "', but the building type does not reference this group"
                );
            }

            for (String methodId : group.methodIds()) {

                ProductionMethodDefinition method =
                        productionMethodRegistry.get(methodId);

                if (method == null) {
                    throw new IllegalStateException(
                            "Production method group '"
                                    + group.id()
                                    + "' references unknown production method '"
                                    + methodId
                                    + "'"
                    );
                }

                if (!method.groupId()
                        .equals(group.id())) {

                    throw new IllegalStateException(
                            "Production method '"
                                    + methodId
                                    + "' belongs to group '"
                                    + method.groupId()
                                    + "', but is referenced by group '"
                                    + group.id()
                                    + "'"
                    );
                }
            }
        }
    }

    /**
     * 检查：
     *
     * 1. PM 声明的 PMG 是否存在
     * 2. PM 是否真的被对应 PMG 收录
     * 3. PM 引用的 input/output Good 是否存在
     * 4. PM 引用的 Occupation 是否存在
     */
    private static void validateProductionMethods(
            GoodRegistry goodRegistry,
            OccupationRegistry occupationRegistry,
            ProductionMethodGroupRegistry productionMethodGroupRegistry,
            ProductionMethodRegistry productionMethodRegistry
    ) {
        for (ProductionMethodDefinition method
                : productionMethodRegistry.getAll()) {

            ProductionMethodGroupDefinition group =
                    productionMethodGroupRegistry.get(
                            method.groupId()
                    );

            if (group == null) {
                throw new IllegalStateException(
                        "Production method '"
                                + method.id()
                                + "' references unknown production method group '"
                                + method.groupId()
                                + "'"
                );
            }

            if (!group.methodIds()
                    .contains(method.id())) {

                throw new IllegalStateException(
                        "Production method '"
                                + method.id()
                                + "' declares group '"
                                + method.groupId()
                                + "', but the group does not contain this method"
                );
            }

            validateGoods(
                    method,
                    method.inputChanges(),
                    "input",
                    goodRegistry
            );

            validateGoods(
                    method,
                    method.outputChanges(),
                    "output",
                    goodRegistry
            );

            for (String occupationId
                    : method.workerChanges().keySet()) {

                if (!occupationRegistry.contains(occupationId)) {
                    throw new IllegalStateException(
                            "Production method '"
                                    + method.id()
                                    + "' references unknown occupation '"
                                    + occupationId
                                    + "'"
                    );
                }
            }
        }
    }

    private static void validateGoods(
            ProductionMethodDefinition method,
            java.util.Map<String, Double> goods,
            String type,
            GoodRegistry goodRegistry
    ) {
        for (String goodId : goods.keySet()) {

            if (!goodRegistry.contains(goodId)) {
                throw new IllegalStateException(
                        "Production method '"
                                + method.id()
                                + "' references unknown "
                                + type
                                + " good '"
                                + goodId
                                + "'"
                );
            }
        }
    }
}
