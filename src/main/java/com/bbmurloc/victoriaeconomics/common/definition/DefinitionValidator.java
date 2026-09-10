package com.bbmurloc.victoriaeconomics.common.definition;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.good.GoodRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.industry.IndustryRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodGroupDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodGroupRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodRegistry;

public final class DefinitionValidator {

    private DefinitionValidator() {
    }

    public static void validateAll(
            IndustryRegistry industryRegistry,
            GoodRegistry goodRegistry,
            BuildingTypeRegistry buildingTypeRegistry,
            ProductionMethodGroupRegistry productionMethodGroupRegistry,
            ProductionMethodRegistry productionMethodRegistry
    ) {
        validateBuildingTypes(
                industryRegistry,
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
            BuildingTypeRegistry buildingTypeRegistry,
            ProductionMethodGroupRegistry productionMethodGroupRegistry
    ) {
        for (BuildingTypeDefinition buildingType
                : buildingTypeRegistry.getAll()) {

            if (!industryRegistry.contains(
                    buildingType.getIndustryId()
            )) {
                throw new IllegalStateException(
                        "Building type '"
                                + buildingType.getId()
                                + "' references unknown industry '"
                                + buildingType.getIndustryId()
                                + "'"
                );
            }

            for (String groupId
                    : buildingType.getProductionMethodGroupIds()) {

                ProductionMethodGroupDefinition group =
                        productionMethodGroupRegistry.get(groupId);

                if (group == null) {
                    throw new IllegalStateException(
                            "Building type '"
                                    + buildingType.getId()
                                    + "' references unknown production method group '"
                                    + groupId
                                    + "'"
                    );
                }

                if (!group.getBuildingTypeId()
                        .equals(buildingType.getId())) {

                    throw new IllegalStateException(
                            "Production method group '"
                                    + groupId
                                    + "' belongs to building type '"
                                    + group.getBuildingTypeId()
                                    + "', but is referenced by building type '"
                                    + buildingType.getId()
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
                            group.getBuildingTypeId()
                    );

            if (buildingType == null) {
                throw new IllegalStateException(
                        "Production method group '"
                                + group.getId()
                                + "' references unknown building type '"
                                + group.getBuildingTypeId()
                                + "'"
                );
            }

            if (!buildingType
                    .getProductionMethodGroupIds()
                    .contains(group.getId())) {

                throw new IllegalStateException(
                        "Production method group '"
                                + group.getId()
                                + "' belongs to building type '"
                                + buildingType.getId()
                                + "', but the building type does not reference this group"
                );
            }

            for (String methodId : group.getMethodIds()) {

                ProductionMethodDefinition method =
                        productionMethodRegistry.get(methodId);

                if (method == null) {
                    throw new IllegalStateException(
                            "Production method group '"
                                    + group.getId()
                                    + "' references unknown production method '"
                                    + methodId
                                    + "'"
                    );
                }

                if (!method.getGroupId()
                        .equals(group.getId())) {

                    throw new IllegalStateException(
                            "Production method '"
                                    + methodId
                                    + "' belongs to group '"
                                    + method.getGroupId()
                                    + "', but is referenced by group '"
                                    + group.getId()
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
     */
    private static void validateProductionMethods(
            GoodRegistry goodRegistry,
            ProductionMethodGroupRegistry productionMethodGroupRegistry,
            ProductionMethodRegistry productionMethodRegistry
    ) {
        for (ProductionMethodDefinition method
                : productionMethodRegistry.getAll()) {

            ProductionMethodGroupDefinition group =
                    productionMethodGroupRegistry.get(
                            method.getGroupId()
                    );

            if (group == null) {
                throw new IllegalStateException(
                        "Production method '"
                                + method.getId()
                                + "' references unknown production method group '"
                                + method.getGroupId()
                                + "'"
                );
            }

            if (!group.getMethodIds()
                    .contains(method.getId())) {

                throw new IllegalStateException(
                        "Production method '"
                                + method.getId()
                                + "' declares group '"
                                + method.getGroupId()
                                + "', but the group does not contain this method"
                );
            }

            validateGoods(
                    method,
                    method.getInputChanges(),
                    "input",
                    goodRegistry
            );

            validateGoods(
                    method,
                    method.getOutputChanges(),
                    "output",
                    goodRegistry
            );
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
                                + method.getId()
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