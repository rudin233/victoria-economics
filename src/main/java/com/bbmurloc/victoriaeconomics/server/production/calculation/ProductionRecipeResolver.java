package com.bbmurloc.victoriaeconomics.server.production.calculation;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodGroupDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodGroupRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.ProductionMethodRegistry;
import com.bbmurloc.victoriaeconomics.server.building.EconomicBuilding;

import java.util.HashMap;
import java.util.Map;

public final class ProductionRecipeResolver {

    private final BuildingTypeRegistry buildingTypeRegistry;
    private final ProductionMethodGroupRegistry productionMethodGroupRegistry;
    private final ProductionMethodRegistry productionMethodRegistry;

    public ProductionRecipeResolver(
            BuildingTypeRegistry buildingTypeRegistry,
            ProductionMethodGroupRegistry productionMethodGroupRegistry,
            ProductionMethodRegistry productionMethodRegistry
    ) {
        this.buildingTypeRegistry = buildingTypeRegistry;
        this.productionMethodGroupRegistry = productionMethodGroupRegistry;
        this.productionMethodRegistry = productionMethodRegistry;
    }

    public ResolvedProductionRecipe resolve(
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

        Map<String, Double> inputs =
                new HashMap<>();

        Map<String, Double> outputs =
                new HashMap<>();

        Map<String, Integer> requiredWorkers =
                new HashMap<>();

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

            String selectedMethodId =
                    building
                            .getProductionDepartment()
                            .getSelectedProductionMethodId(
                                    groupId
                            );

            if (selectedMethodId == null) {
                throw new IllegalStateException(
                        "Building "
                                + building.getId()
                                + " has no selected production method for group '"
                                + groupId
                                + "'"
                );
            }

            ProductionMethodDefinition method =
                    productionMethodRegistry.get(
                            selectedMethodId
                    );

            if (method == null) {
                throw new IllegalStateException(
                        "Unknown production method: "
                                + selectedMethodId
                );
            }

            if (!method.groupId().equals(groupId)) {
                throw new IllegalStateException(
                        "Production method '"
                                + selectedMethodId
                                + "' does not belong to group '"
                                + groupId
                                + "'"
                );
            }

            mergeDoubleChanges(
                    inputs,
                    method.inputChanges()
            );

            mergeDoubleChanges(
                    outputs,
                    method.outputChanges()
            );

            mergeIntegerChanges(
                    requiredWorkers,
                    method.workerChanges()
            );
        }

        removeZeros(inputs);
        removeZeros(outputs);
        removeZeroWorkers(requiredWorkers);

        validateResolvedValues(
                inputs,
                outputs,
                requiredWorkers
        );

        return new ResolvedProductionRecipe(
                inputs,
                outputs,
                requiredWorkers
        );
    }

    private static void mergeDoubleChanges(
            Map<String, Double> target,
            Map<String, Double> changes
    ) {
        for (Map.Entry<String, Double> entry
                : changes.entrySet()) {

            target.merge(
                    entry.getKey(),
                    entry.getValue(),
                    Double::sum
            );
        }
    }

    private static void mergeIntegerChanges(
            Map<String, Integer> target,
            Map<String, Integer> changes
    ) {
        for (Map.Entry<String, Integer> entry
                : changes.entrySet()) {

            target.merge(
                    entry.getKey(),
                    entry.getValue(),
                    Integer::sum
            );
        }
    }

    private static void removeZeros(
            Map<String, Double> values
    ) {
        values.entrySet().removeIf(
                entry -> Math.abs(entry.getValue()) < 1.0E-9
        );
    }

    private static void removeZeroWorkers(
            Map<String, Integer> workers
    ) {
        workers.entrySet().removeIf(
                entry -> entry.getValue() == 0
        );
    }

    private static void validateResolvedValues(
            Map<String, Double> inputs,
            Map<String, Double> outputs,
            Map<String, Integer> requiredWorkers
    ) {
        for (Map.Entry<String, Double> entry
                : inputs.entrySet()) {

            if (entry.getValue() < 0) {
                throw new IllegalStateException(
                        "Resolved input quantity cannot be negative: "
                                + entry.getKey()
                                + " = "
                                + entry.getValue()
                );
            }
        }

        for (Map.Entry<String, Double> entry
                : outputs.entrySet()) {

            if (entry.getValue() < 0) {
                throw new IllegalStateException(
                        "Resolved output quantity cannot be negative: "
                                + entry.getKey()
                                + " = "
                                + entry.getValue()
                );
            }
        }

        for (Map.Entry<String, Integer> entry
                : requiredWorkers.entrySet()) {

            if (entry.getValue() < 0) {
                throw new IllegalStateException(
                        "Resolved worker requirement cannot be negative: "
                                + entry.getKey()
                                + " = "
                                + entry.getValue()
                );
            }
        }

        if (outputs.isEmpty()) {
            throw new IllegalStateException(
                    "Resolved production recipe must have at least one output"
            );
        }
    }
}
