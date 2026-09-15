package com.bbmurloc.victoriaeconomics.server.workforce.staffing;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import com.bbmurloc.victoriaeconomics.server.building.EconomicBuilding;
import com.bbmurloc.victoriaeconomics.server.building.department.hr.StaffingExpectation;
import com.bbmurloc.victoriaeconomics.server.workforce.employment.EmploymentRegistry;
import com.bbmurloc.victoriaeconomics.server.production.calculation.EquipmentCapacityCalculator;
import com.bbmurloc.victoriaeconomics.server.production.calculation.ProductionRecipeResolver;
import com.bbmurloc.victoriaeconomics.server.production.calculation.ResolvedProductionRecipe;
import com.bbmurloc.victoriaeconomics.server.productionequipment.ProductionEquipmentRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class StaffingCalculator {

    /**
     * 每一种必需职业至少达到 10%，
     * 才允许开始生产。
     */
    public static final double MINIMUM_START_RATIO =
            0.10;

    private static final double EPSILON =
            1.0E-9;

    private final EmploymentRegistry
            employmentRegistry;

    private final BuildingTypeRegistry
            buildingTypeRegistry;

    private final ProductionRecipeResolver
            productionRecipeResolver;

    private final ProductionEquipmentRegistry
            productionEquipmentRegistry;

    public StaffingCalculator(
            EmploymentRegistry employmentRegistry,
            BuildingTypeRegistry buildingTypeRegistry,
            ProductionRecipeResolver productionRecipeResolver,
            ProductionEquipmentRegistry productionEquipmentRegistry
    ) {
        this.employmentRegistry =
                Objects.requireNonNull(
                        employmentRegistry
                );

        this.buildingTypeRegistry =
                Objects.requireNonNull(
                        buildingTypeRegistry
                );

        this.productionRecipeResolver =
                Objects.requireNonNull(
                        productionRecipeResolver
                );

        this.productionEquipmentRegistry =
                Objects.requireNonNull(
                        productionEquipmentRegistry,
                        "productionEquipmentRegistry cannot be null"
                );
    }

    public StaffingSnapshot calculate(
            EconomicBuilding building
    ) {
        Objects.requireNonNull(
                building,
                "building cannot be null"
        );

        /*
         * 当前 Live PM 配置解析出来的 Recipe。
         */
        ResolvedProductionRecipe recipe =
                productionRecipeResolver.resolve(
                        building
                );

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

        /*
         * e = installedProductionEquipment / maxEquipment
         */
        int installedProductionEquipment =
                productionEquipmentRegistry
                        .getInstalledQuantity(
                                building.getId()
                        );

        double equipmentCapacity =
                EquipmentCapacityCalculator.calculate(
                        installedProductionEquipment,
                        buildingType.maxProductionEquipment()
                );

        StaffingExpectation staffingExpectation =
                building
                        .getHumanResourcesDepartment()
                        .getStaffingPlan();

        Map<String, OccupationStaffingSnapshot>
                occupationSnapshots =
                new HashMap<>();

        /*
         * 如果当前 Recipe 不需要任何员工，
         * staffing 本身不构成生产限制。
         */
        if (recipe.requiredWorkers().isEmpty()) {

            return new StaffingSnapshot(
                    equipmentCapacity,
                    occupationSnapshots,
                    true,
                    1.0
            );
        }

        boolean canStart =
                true;

        double minimumStaffingRatio =
                1.0;

        for (Map.Entry<String, Integer> entry
                : recipe.requiredWorkers()
                .entrySet()) {

            String occupationId =
                    entry.getKey();

            int requiredWorkers =
                    entry.getValue();

            if (requiredWorkers <= 0) {
                throw new IllegalStateException(
                        "Resolved required worker count must be positive: "
                                + occupationId
                );
            }

            /*
             * HR希望达到多少比例。
             */
            double targetRatio =
                    staffingExpectation.targetRatio(
                            occupationId
                    );

            /*
             * 例如：
             *
             * N = 10
             * target = 0.75
             *
             * ceil(7.5) = 8
             *
             * 因为目标是“至少达到75%”。
             */
            int targetWorkers =
                    calculateTargetWorkers(
                            requiredWorkers,
                            targetRatio
                    );

            /*
             * floor(e * N_i)
             */
            int equipmentLimitedMaximum =
                    calculateEquipmentLimit(
                            requiredWorkers,
                            equipmentCapacity
                    );

            /*
             * HR想招80，
             * 但设备现在只能容纳50，
             * 当前实际招聘目标就是50。
             */
            int hiringTargetWorkers =
                    Math.min(
                            targetWorkers,
                            equipmentLimitedMaximum
                    );

            /*
             * 实际劳动关系中有多少员工。
             */
            int employedWorkers =
                    employmentRegistry.count(
                            building.getId(),
                            occupationId
                    );

            /*
             * 如果因为换 PM / 出售设备等原因
             * 当前实际员工超过设备上限，
             * 超出的员工暂时不能提供有效生产力。
             */
            int effectiveWorkers =
                    Math.min(
                            employedWorkers,
                            equipmentLimitedMaximum
                    );

            /*
             * 真正生产用的满足率：
             *
             * r_i = effectiveWorkers / N_i
             *
             * 注意：
             * 这里完全不使用 targetRatio。
             *
             * HR目标不会凭空制造生产力。
             */
            double staffingRatio =
                    (double) effectiveWorkers
                            / requiredWorkers;

            staffingRatio =
                    Math.min(
                            staffingRatio,
                            1.0
                    );

            minimumStaffingRatio =
                    Math.min(
                            minimumStaffingRatio,
                            staffingRatio
                    );

            /*
             * 所有必需职业都必须至少达到10%。
             */
            if (staffingRatio
                    + EPSILON
                    < MINIMUM_START_RATIO) {

                canStart =
                        false;
            }

            OccupationStaffingSnapshot snapshot =
                    new OccupationStaffingSnapshot(
                            occupationId,
                            requiredWorkers,
                            targetRatio,
                            targetWorkers,
                            equipmentLimitedMaximum,
                            hiringTargetWorkers,
                            employedWorkers,
                            effectiveWorkers,
                            staffingRatio
                    );

            occupationSnapshots.put(
                    occupationId,
                    snapshot
            );
        }

        /*
         * 只有满足最低开工条件以后：
         *
         * s = min_i r_i
         *
         * 否则：
         *
         * s = 0
         */
        double productionSpeed =
                canStart
                        ? minimumStaffingRatio
                        : 0.0;

        return new StaffingSnapshot(
                equipmentCapacity,
                occupationSnapshots,
                canStart,
                productionSpeed
        );
    }

    private static int calculateTargetWorkers(
            int requiredWorkers,
            double targetRatio
    ) {
        if (targetRatio <= 0.0) {
            return 0;
        }

        return Math.min(
                requiredWorkers,
                (int) Math.ceil(
                        requiredWorkers
                                * targetRatio
                                - EPSILON
                )
        );
    }

    private static int calculateEquipmentLimit(
            int requiredWorkers,
            double equipmentCapacity
    ) {
        if (equipmentCapacity <= 0.0) {
            return 0;
        }

        return Math.min(
                requiredWorkers,
                (int) Math.floor(
                        requiredWorkers
                                * equipmentCapacity
                                + EPSILON
                )
        );
    }
}
