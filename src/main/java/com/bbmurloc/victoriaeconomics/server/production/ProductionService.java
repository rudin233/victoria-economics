package com.bbmurloc.victoriaeconomics.server.production;

import com.bbmurloc.victoriaeconomics.server.building.BuildingRegistry;
import com.bbmurloc.victoriaeconomics.server.building.BuildingStatus;
import com.bbmurloc.victoriaeconomics.server.building.EconomicBuilding;
import com.bbmurloc.victoriaeconomics.server.building.department.production.ProductionDepartment;
import com.bbmurloc.victoriaeconomics.server.production.batch.ProductionBatch;
import com.bbmurloc.victoriaeconomics.server.production.batch.ProductionBatchConfiguration;
import com.bbmurloc.victoriaeconomics.server.production.calculation.ProductionRecipeResolver;
import com.bbmurloc.victoriaeconomics.server.production.calculation.ResolvedProductionRecipe;
import com.bbmurloc.victoriaeconomics.server.workforce.employment.EmploymentRegistry;
import com.bbmurloc.victoriaeconomics.server.workforce.staffing.OccupationStaffingSnapshot;
import com.bbmurloc.victoriaeconomics.server.workforce.staffing.StaffingCalculator;
import com.bbmurloc.victoriaeconomics.server.workforce.staffing.StaffingSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ProductionService {

    private final BuildingRegistry buildingRegistry;

    private final ProductionRecipeResolver
            productionRecipeResolver;

    private final StaffingCalculator
            staffingCalculator;

    private final EmploymentRegistry
            employmentRegistry;

    public ProductionService(
            BuildingRegistry buildingRegistry,
            ProductionRecipeResolver productionRecipeResolver,
            StaffingCalculator staffingCalculator,
            EmploymentRegistry employmentRegistry
    ) {
        this.buildingRegistry =
                Objects.requireNonNull(
                        buildingRegistry
                );

        this.productionRecipeResolver =
                Objects.requireNonNull(
                        productionRecipeResolver
                );

        this.staffingCalculator =
                Objects.requireNonNull(
                        staffingCalculator
                );

        this.employmentRegistry =
                Objects.requireNonNull(
                        employmentRegistry
                );
    }

    /**
     * 根据建筑当前的 Live State
     * 创建并启动一批新的生产。
     *
     * 此方法负责把：
     *
     * PM selection
     * ResolvedRecipe
     * Equipment Capacity
     * StaffingSnapshot
     * 实际参与员工
     *
     * 一次性冻结进 ProductionBatchConfiguration。
     */
    public ProductionBatch startBatch(
            UUID buildingId
    ) {
        Objects.requireNonNull(
                buildingId,
                "buildingId cannot be null"
        );

        EconomicBuilding building =
                requireBuilding(
                        buildingId
                );

        if (building.getStatus()
                != BuildingStatus.ACTIVE) {

            throw new IllegalStateException(
                    "Building is not active: "
                            + buildingId
            );
        }

        ProductionDepartment productionDepartment =
                building.getProductionDepartment();

        /*
         * 即使原 Batch 已经 COMPLETED / ABORTED，
         * 也必须先完成结算并 clear，
         * 不能直接覆盖。
         */
        if (productionDepartment
                .getActiveBatch() != null) {

            throw new IllegalStateException(
                    "Building already contains a production batch"
            );
        }

        /*
         * 按当前 PM 解析生产要求。
         */
        ResolvedProductionRecipe recipe =
                productionRecipeResolver.resolve(
                        building
                );

        /*
         * 根据当前 Employment、Equipment 和 HR Plan
         * 计算当前人员配置。
         */
        StaffingSnapshot staffing =
                staffingCalculator.calculate(
                        building
                );

        if (!staffing.canStart()) {
            throw new IllegalStateException(
                    "Building does not meet minimum staffing requirements"
            );
        }

        /*
         * 锁定真正参与本批生产的 NPC。
         */
        Map<String, List<UUID>>
                activeEmployeesByOccupation =
                lockActiveEmployees(
                        building,
                        staffing
                );

        /*
         * 创建不可变批次配置。
         */
        ProductionBatchConfiguration configuration =
                new ProductionBatchConfiguration(
                        productionDepartment
                                .getSelectedProductionMethods(),
                        recipe,
                        staffing.equipmentCapacity(),
                        staffing,
                        activeEmployeesByOccupation
                );

        ProductionBatch batch =
                new ProductionBatch(
                        UUID.randomUUID(),
                        buildingId,
                        configuration
                );

        productionDepartment.startBatch(
                batch
        );

        return batch;
    }

    /**
     * 手动推进当前生产批次。
     *
     * fullSpeedProgressDelta 的含义：
     *
     * 如果速度 = 1.0，
     * 这次应该推进多少标准进度。
     *
     * 真正的 EconomicClock 接入以后，
     * 会由时钟系统提供这个值。
     */
    public ProductionBatch advanceBatch(
            UUID buildingId,
            double fullSpeedProgressDelta
    ) {
        ProductionBatch batch =
                requireBatch(
                        buildingId
                );

        batch.advance(
                fullSpeedProgressDelta
        );

        return batch;
    }

    /**
     * 中止当前生产批次。
     *
     * 当前阶段只修改 Batch 状态。
     *
     * 以后 Warehouse / Wage 接入后，
     * 这里还会负责：
     *
     * 返还 reserved inputs
     * 结算实际劳动工资
     * 不产生产品
     */
    public ProductionBatch abortBatch(
            UUID buildingId
    ) {
        ProductionBatch batch =
                requireBatch(
                        buildingId
                );

        batch.abort();

        return batch;
    }

    /**
     * 清除已经结束的批次。
     *
     * ACTIVE Batch 不能被清除。
     */
    public void clearFinishedBatch(
            UUID buildingId
    ) {
        EconomicBuilding building =
                requireBuilding(
                        buildingId
                );

        building
                .getProductionDepartment()
                .clearFinishedBatch();
    }

    /**
     * 根据 StaffingSnapshot 中的 effectiveWorkers，
     * 锁定本批真正参加劳动的 NPC。
     */
    private Map<String, List<UUID>>
    lockActiveEmployees(
            EconomicBuilding building,
            StaffingSnapshot staffing
    ) {
        Map<String, List<UUID>> result =
                new HashMap<>();

        for (Map.Entry<String, OccupationStaffingSnapshot> entry
                : staffing
                .occupations()
                .entrySet()) {

            String occupationId =
                    entry.getKey();

            int effectiveWorkers =
                    entry.getValue()
                            .effectiveWorkers();

            /*
             * 从当前真实 Employment 中取出
             * 这个建筑、这个职业的所有员工。
             */
            List<UUID> employeeIds =
                    new ArrayList<>(
                            employmentRegistry
                                    .getEmployeeIds(
                                            building.getId(),
                                            occupationId
                                    )
                    );

            /*
             * Set 本身没有稳定顺序。
             *
             * 排序后，每次在相同 Employment 状态下
             * 都会锁定同一批 NPC。
             */
            employeeIds.sort(
                    Comparator.naturalOrder()
            );

            if (employeeIds.size()
                    < effectiveWorkers) {

                throw new IllegalStateException(
                        "Employment index contains fewer workers than StaffingSnapshot requires for occupation '"
                                + occupationId
                                + "'"
                );
            }

            /*
             * 只锁定 effectiveWorkers 数量。
             *
             * 如果实际雇员 80，
             * 但设备只能让 50 人有效工作，
             * 当前 Batch 只锁定 50 人。
             */
            List<UUID> lockedEmployees =
                    List.copyOf(
                            employeeIds.subList(
                                    0,
                                    effectiveWorkers
                            )
                    );

            result.put(
                    occupationId,
                    lockedEmployees
            );
        }

        return Map.copyOf(
                result
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

    private ProductionBatch requireBatch(
            UUID buildingId
    ) {
        EconomicBuilding building =
                requireBuilding(
                        buildingId
                );

        ProductionBatch batch =
                building
                        .getProductionDepartment()
                        .getActiveBatch();

        if (batch == null) {
            throw new IllegalStateException(
                    "Building has no production batch: "
                            + buildingId
            );
        }

        return batch;
    }
}