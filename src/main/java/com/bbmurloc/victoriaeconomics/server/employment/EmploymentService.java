package com.bbmurloc.victoriaeconomics.server.employment;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeDefinition;
import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.occupation.OccupationRegistry;
import com.bbmurloc.victoriaeconomics.server.building.BuildingRegistry;
import com.bbmurloc.victoriaeconomics.server.building.EconomicBuilding;
import com.bbmurloc.victoriaeconomics.server.production.EquipmentCapacityCalculator;
import com.bbmurloc.victoriaeconomics.server.production.ProductionRecipeResolver;
import com.bbmurloc.victoriaeconomics.server.production.ResolvedProductionRecipe;

import java.util.Objects;
import java.util.UUID;

public final class EmploymentService {

    private final EmploymentRegistry employmentRegistry;

    private final BuildingRegistry buildingRegistry;

    private final BuildingTypeRegistry buildingTypeRegistry;

    private final OccupationRegistry occupationRegistry;

    private final ProductionRecipeResolver productionRecipeResolver;

    public EmploymentService(
            EmploymentRegistry employmentRegistry,
            BuildingRegistry buildingRegistry,
            BuildingTypeRegistry buildingTypeRegistry,
            OccupationRegistry occupationRegistry,
            ProductionRecipeResolver productionRecipeResolver
    ) {
        this.employmentRegistry =
                Objects.requireNonNull(
                        employmentRegistry
                );

        this.buildingRegistry =
                Objects.requireNonNull(
                        buildingRegistry
                );

        this.buildingTypeRegistry =
                Objects.requireNonNull(
                        buildingTypeRegistry
                );

        this.occupationRegistry =
                Objects.requireNonNull(
                        occupationRegistry
                );

        this.productionRecipeResolver =
                Objects.requireNonNull(
                        productionRecipeResolver
                );
    }

    /**
     * 招聘一名员工。
     *
     * 当前规则：
     *
     * 1. occupation 必须存在；
     * 2. NPC 不能已经有工作；
     * 3. Building 必须存在；
     * 4. 当前生产配方必须需要该职业；
     * 5. 当前设备能力必须允许继续招聘。
     */
    public EmploymentRecord hire(
            UUID employeeId,
            UUID buildingId,
            String occupationId
    ) {
        Objects.requireNonNull(
                employeeId,
                "employeeId cannot be null"
        );

        Objects.requireNonNull(
                buildingId,
                "buildingId cannot be null"
        );

        Objects.requireNonNull(
                occupationId,
                "occupationId cannot be null"
        );

        /*
         * 1. 职业是否合法。
         */
        if (!occupationRegistry.contains(
                occupationId
        )) {
            throw new IllegalArgumentException(
                    "Unknown occupation: "
                            + occupationId
            );
        }

        /*
         * 2. 一个 NPC 当前最多有一份工作。
         */
        if (employmentRegistry.isEmployed(
                employeeId
        )) {
            throw new IllegalStateException(
                    "Employee is already employed: "
                            + employeeId
            );
        }

        /*
         * 3. 找实际经济建筑。
         */
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

        /*
         * 4. 根据这栋建筑当前选择的 PM，
         *    得到最终生产配方。
         */
        ResolvedProductionRecipe recipe =
                productionRecipeResolver.resolve(
                        building
                );

        Integer requiredWorkers =
                recipe.requiredWorkers()
                        .get(
                                occupationId
                        );

        /*
         * 当前配方根本不需要这种职业，
         * 不允许招聘。
         */
        if (requiredWorkers == null
                || requiredWorkers <= 0) {

            throw new IllegalStateException(
                    "Building does not currently require occupation '"
                            + occupationId
                            + "'"
            );
        }

        /*
         * 5. 找 BuildingType，
         *    获取 maxEquipment。
         */
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
         * e = currentEquipment / maxEquipment
         */
        double equipmentCapacity =
                EquipmentCapacityCalculator.calculate(
                        building.getCurrentEquipment(),
                        buildingType.maxEquipment()
                );

        /*
         * 每种职业可雇佣人数上限：
         *
         * floor(e * N_i)
         */
        int equipmentLimitedMaximum =
                (int) Math.floor(
                        equipmentCapacity
                                * requiredWorkers
                );

        int currentWorkers =
                employmentRegistry.count(
                        buildingId,
                        occupationId
                );

        if (currentWorkers
                >= equipmentLimitedMaximum) {

            throw new IllegalStateException(
                    "Cannot hire more workers for occupation '"
                            + occupationId
                            + "'. Current="
                            + currentWorkers
                            + ", equipment-limited maximum="
                            + equipmentLimitedMaximum
            );
        }

        EmploymentRecord record =
                new EmploymentRecord(
                        employeeId,
                        buildingId,
                        occupationId
                );

        employmentRegistry.add(
                record
        );

        return record;
    }

    /**
     * 解雇。
     *
     * 当前只是删除 active EmploymentRecord。
     * 工资结算、欠薪等以后再接。
     */
    public EmploymentRecord fire(
            UUID employeeId
    ) {
        Objects.requireNonNull(
                employeeId,
                "employeeId cannot be null"
        );

        return employmentRegistry.remove(
                employeeId
        );
    }

    public EmploymentRecord getEmployment(
            UUID employeeId
    ) {
        return employmentRegistry.getByEmployee(
                employeeId
        );
    }

    public boolean isEmployed(
            UUID employeeId
    ) {
        return employmentRegistry.isEmployed(
                employeeId
        );
    }

    public int countWorkers(
            UUID buildingId,
            String occupationId
    ) {
        return employmentRegistry.count(
                buildingId,
                occupationId
        );
    }
}