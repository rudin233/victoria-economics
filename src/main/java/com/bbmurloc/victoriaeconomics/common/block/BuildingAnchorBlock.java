package com.bbmurloc.victoriaeconomics.common.block;

import com.bbmurloc.victoriaeconomics.common.blockentity.BuildingAnchorBlockEntity;
import com.bbmurloc.victoriaeconomics.server.ServerEconomyContext;
import com.bbmurloc.victoriaeconomics.server.ServerEconomyRuntime;
import com.bbmurloc.victoriaeconomics.server.building.EconomicBuilding;
import com.bbmurloc.victoriaeconomics.server.production.batch.ProductionBatch;
import com.bbmurloc.victoriaeconomics.server.production.calculation.ResolvedProductionRecipe;
import com.bbmurloc.victoriaeconomics.server.productionequipment.ProductionEquipmentHolding;
import com.bbmurloc.victoriaeconomics.server.productionequipment.ProductionEquipmentService;
import com.bbmurloc.victoriaeconomics.server.productionequipment.operation.ProductionEquipmentOperationSubmission;
import com.bbmurloc.victoriaeconomics.server.workforce.staffing.StaffingSnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BuildingAnchorBlock
        extends Block
        implements EntityBlock {

    public BuildingAnchorBlock(
            Properties properties
    ) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(
            BlockPos pos,
            BlockState state
    ) {
        return new BuildingAnchorBlockEntity(
                pos,
                state
        );
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hitResult
    ) {
        /*
         * 所有经济逻辑只在逻辑服务端执行。
         */
        if (!level.isClientSide
                && level instanceof ServerLevel serverLevel) {

            BlockEntity blockEntity =
                    level.getBlockEntity(pos);

            if (blockEntity
                    instanceof BuildingAnchorBlockEntity anchor) {

                MinecraftServer server =
                        serverLevel.getServer();

                ServerEconomyContext context =
                        ServerEconomyRuntime.get(server);

                /*
                 * Anchor 尚未绑定 EconomicBuilding：
                 * 第一次右键创建测试建筑。
                 */
                if (anchor.getBuildingId() == null) {

                    createTestBuilding(
                            context,
                            anchor,
                            player
                    );

                } else {

                    EconomicBuilding building =
                            context.getBuildingRegistry()
                                    .get(
                                            anchor.getBuildingId()
                                    );

                    if (building == null) {

                        player.sendSystemMessage(
                                Component.literal(
                                        "Building not found: "
                                                + anchor.getBuildingId()
                                )
                        );

                        return InteractionResult.SUCCESS;
                    }

                    showBuildingInfo(
                            building,
                            player
                    );

                    /*
                     * 当前唯一主线回归测试：
                     *
                     * ProductionEquipment
                     *        ↓
                     * Staffing
                     *        ↓
                     * ProductionBatch
                     *        ↓
                     * Deferred Uninstall
                     *        ↓
                     * Batch Boundary
                     *        ↓
                     * ProductionEquipmentHolding
                     */
                    runProductionEquipmentAuthorityRegressionTest(
                            context,
                            building,
                            player
                    );
                }
            }
        }

        return InteractionResult.sidedSuccess(
                level.isClientSide
        );
    }

    // =========================================================
    // Building test setup
    // =========================================================

    /**
     * 创建测试用 tooling_workshop，
     * 并把 EconomicBuilding UUID 绑定到 Anchor。
     */
    private void createTestBuilding(
            ServerEconomyContext context,
            BuildingAnchorBlockEntity anchor,
            Player player
    ) {
        EconomicBuilding building =
                context.getBuildingService()
                        .createBuilding(
                                "tooling_workshop"
                        );

        anchor.setBuildingId(
                building.getId()
        );

        player.sendSystemMessage(
                Component.literal(
                        "Created building: "
                                + building.getId()
                )
        );
    }

    /**
     * 显示当前 Anchor 对应建筑的基本信息。
     */
    private void showBuildingInfo(
            EconomicBuilding building,
            Player player
    ) {
        player.sendSystemMessage(
                Component.literal(
                        "Found building: "
                                + building.getId()
                                + " | type = "
                                + building.getBuildingTypeId()
                                + " | status = "
                                + building.getStatus()
                )
        );
    }

    // =========================================================
    // Current ProductionEquipment regression test
    // =========================================================

    /**
     * 当前 ProductionEquipment 主线回归测试。
     *
     * 预期流程：
     *
     * 1. 初始安装设备 = 50
     * 2. Staffing equipmentCapacity = 0.5
     * 3. 创建 Batch，锁定 capacity = 0.5
     * 4. Active Batch 中请求 uninstall(30)
     * 5. 请求进入 Queue，Holding 仍保持 installed = 50
     * 6. Batch 每次右键推进 0.1
     * 7. Batch COMPLETED 时 ProductionService 自动执行 Boundary flush
     * 8. Holding installed = 20
     * 9. 下一次右键 Staffing equipmentCapacity = 0.2
     */
    private void runProductionEquipmentAuthorityRegressionTest(
            ServerEconomyContext context,
            EconomicBuilding building,
            Player player
    ) {
        /*
         * 只有完全没有 Batch 时才准备初始测试状态。
         *
         * COMPLETED / ABORTED 但尚未 clear 的 Batch
         * 也不能偷偷把设备重置回 50。
         */
        if (building
                .getProductionDepartment()
                .getActiveBatch() == null) {

            ensureTestInstalledEquipment(
                    context,
                    building,
                    50
            );
        }

        /*
         * 显示本次处理前真实 ProductionEquipment 状态。
         */
        showProductionEquipmentState(
                context,
                building,
                player,
                "LIVE EQUIPMENT BEFORE"
        );

        showResolvedRecipe(
                context,
                building,
                player
        );

        /*
         * 初始 capacity=0.5 时：
         *
         * laborer   = 5 / 10
         * machinist = 2 / 4
         */
        ensureTestEmployees(
                context,
                building
        );

        /*
         * 这里现在已经从 ProductionEquipmentRegistry
         * 读取真实 installedQuantity。
         */
        showStaffing(
                context,
                building,
                player
        );

        /*
         * 如果没有 Batch：
         *
         * - 创建 Batch
         * - 锁定当前 capacity
         * - 提交 uninstall(30)
         *
         * Active Batch 中 uninstall 应进入 Queue。
         */
        runProductionBatchLockTest(
                context,
                building,
                player
        );

        /*
         * 每次右键推进一次 Batch。
         *
         * 当 ACTIVE -> COMPLETED 时，
         * ProductionService 会自动执行
         * handleBatchBoundary()。
         */
        runProductionBatchProgressTest(
                context,
                building,
                player
        );

        /*
         * 特别重要：
         *
         * 在 Batch 完成的那次右键中，
         * 这里应该已经能够直接观察到
         * 自动 flush 后的新 Holding。
         */
        showProductionEquipmentState(
                context,
                building,
                player,
                "LIVE EQUIPMENT AFTER"
        );
    }

    // =========================================================
    // ProductionEquipment setup / state
    // =========================================================

    /**
     * 临时确保当前已安装 ProductionEquipment 数量等于 targetInstalled。
     *
     * 该 helper 只能在：
     *
     * - 没有 Active Batch
     * - 没有 pending ProductionEquipment operation
     *
     * 时执行。
     */
    private static void ensureTestInstalledEquipment(
            ServerEconomyContext context,
            EconomicBuilding building,
            int targetInstalled
    ) {
        if (targetInstalled < 0) {
            throw new IllegalArgumentException(
                    "targetInstalled cannot be negative"
            );
        }

        ProductionEquipmentService service =
                context.getProductionEquipmentService();

        if (building
                .getProductionDepartment()
                .hasActiveBatch()) {

            throw new IllegalStateException(
                    "Cannot force test equipment while production batch is active"
            );
        }

        if (service.hasPendingOperationsForBuilding(
                building.getId()
        )) {
            throw new IllegalStateException(
                    "Cannot force test equipment while production equipment "
                            + "operations are pending"
            );
        }

        ProductionEquipmentHolding holding =
                service.getHolding(
                        building.getId()
                );

        int installed =
                holding == null
                        ? 0
                        : holding.getInstalledQuantity();

        if (installed == targetInstalled) {
            return;
        }

        /*
         * 当前安装数量不足：
         *
         * 先确保仓库内有足够的未安装设备，
         * 再正式 install。
         */
        if (installed < targetInstalled) {

            int needed =
                    targetInstalled - installed;

            int availableUninstalled =
                    holding == null
                            ? 0
                            : holding.getUninstalledQuantity();

            if (availableUninstalled < needed) {
                service.addUninstalledEquipment(
                        building.getId(),
                        needed - availableUninstalled
                );
            }

            service.install(
                    building.getId(),
                    needed
            );

            return;
        }

        /*
         * 当前安装数量过多：
         * 正式走 ProductionEquipmentService.uninstall。
         */
        service.uninstall(
                building.getId(),
                installed - targetInstalled
        );
    }

    /**
     * 显示真实 ProductionEquipment Holding，
     * 同时显示该建筑当前 pending operation 数量。
     */
    private void showProductionEquipmentState(
            ServerEconomyContext context,
            EconomicBuilding building,
            Player player,
            String label
    ) {
        ProductionEquipmentHolding holding =
                context.getProductionEquipmentService()
                        .getHolding(
                                building.getId()
                        );

        int pending =
                context.getProductionEquipmentOperationQueue()
                        .getForBuilding(
                                building.getId()
                        )
                        .size();

        if (holding == null) {

            player.sendSystemMessage(
                    Component.literal(
                            "["
                                    + label
                                    + "] no holding"
                                    + " | pending="
                                    + pending
                    )
            );

            return;
        }

        player.sendSystemMessage(
                Component.literal(
                        "["
                                + label
                                + "] total="
                                + holding.getTotalQuantity()
                                + " | installed="
                                + holding.getInstalledQuantity()
                                + " | uninstalled="
                                + holding.getUninstalledQuantity()
                                + " | pending="
                                + pending
                )
        );
    }

    // =========================================================
    // Recipe / Employment / Staffing
    // =========================================================

    /**
     * 显示当前 PM 组合解析得到的生产配方。
     */
    private void showResolvedRecipe(
            ServerEconomyContext context,
            EconomicBuilding building,
            Player player
    ) {
        ResolvedProductionRecipe recipe =
                context.getProductionRecipeResolver()
                        .resolve(
                                building
                        );

        player.sendSystemMessage(
                Component.literal(
                        "Recipe = "
                                + recipe
                )
        );
    }

    /**
     * 为当前测试建筑准备员工：
     *
     * laborer   = 5
     * machinist = 2
     *
     * 对应初始 equipmentCapacity = 0.5。
     */
    private void ensureTestEmployees(
            ServerEconomyContext context,
            EconomicBuilding building
    ) {
        ensureTestEmployeeCount(
                context,
                building,
                "laborer",
                5
        );

        ensureTestEmployeeCount(
                context,
                building,
                "machinist",
                2
        );
    }

    /**
     * 确保指定职业具有指定数量的测试 EmploymentRecord。
     */
    private void ensureTestEmployeeCount(
            ServerEconomyContext context,
            EconomicBuilding building,
            String occupationId,
            int count
    ) {
        for (int i = 1; i <= count; i++) {

            UUID employeeId =
                    createTestEmployeeId(
                            building.getId(),
                            occupationId,
                            i
                    );

            if (!context.getEmploymentService()
                    .isEmployed(
                            employeeId
                    )) {

                context.getEmploymentService()
                        .hire(
                                employeeId,
                                building.getId(),
                                occupationId
                        );
            }
        }
    }

    /**
     * 根据：
     *
     * buildingId
     * occupationId
     * index
     *
     * 创建稳定测试员工 UUID。
     */
    private UUID createTestEmployeeId(
            UUID buildingId,
            String occupationId,
            int index
    ) {
        String source =
                buildingId
                        + ":test:"
                        + occupationId
                        + ":"
                        + index;

        return UUID.nameUUIDFromBytes(
                source.getBytes(
                        StandardCharsets.UTF_8
                )
        );
    }

    /**
     * 显示当前 StaffingSnapshot。
     *
     * StaffingCalculator 现在已经以
     * ProductionEquipmentRegistry.installedQuantity
     * 作为设备权威数据源。
     */
    private void showStaffing(
            ServerEconomyContext context,
            EconomicBuilding building,
            Player player
    ) {
        StaffingSnapshot staffing =
                context.getStaffingCalculator()
                        .calculate(
                                building
                        );

        player.sendSystemMessage(
                Component.literal(
                        "Staffing = "
                                + staffing
                )
        );
    }

    // =========================================================
    // ProductionBatch / Batch-Boundary Reconfiguration
    // =========================================================

    /**
     * 创建 Batch 并测试：
     *
     * Batch 启动以后提交 uninstall(30)。
     *
     * 正确行为：
     *
     * installed = 50
     * Active Batch
     * uninstall(30)
     *
     *         ↓
     *
     * Submission = QUEUED
     * Holding installed 仍然 = 50
     * pending = 1
     *
     * 当前 Batch 继续使用锁定的 capacity = 0.5。
     */
    private void runProductionBatchLockTest(
            ServerEconomyContext context,
            EconomicBuilding building,
            Player player
    ) {
        ProductionBatch existingBatch =
                building
                        .getProductionDepartment()
                        .getActiveBatch();

        /*
         * 已经存在 Batch 时不重复创建。
         */
        if (existingBatch != null) {

            player.sendSystemMessage(
                    Component.literal(
                            "[EXISTING BATCH] "
                                    + "id="
                                    + existingBatch.getId()
                                    + " | status="
                                    + existingBatch.getStatus()
                                    + " | progress="
                                    + existingBatch.getProgress()
                                    + " | lockedEquipment="
                                    + existingBatch
                                    .getConfiguration()
                                    .equipmentCapacity()
                                    + " | lockedSpeed="
                                    + existingBatch
                                    .getConfiguration()
                                    .productionSpeed()
                    )
            );

            return;
        }

        /*
         * 此时预期：
         *
         * installedProductionEquipment = 50
         * maxProductionEquipment       = 100
         *
         * equipmentCapacity = 0.5
         *
         * laborer   = 5 / 10
         * machinist = 2 / 4
         *
         * productionSpeed = 0.5
         */
        ProductionBatch batch =
                context.getProductionService()
                        .startBatch(
                                building.getId()
                        );

        player.sendSystemMessage(
                Component.literal(
                        "[BATCH START] "
                                + "id="
                                + batch.getId()
                                + " | lockedEquipment="
                                + batch
                                .getConfiguration()
                                .equipmentCapacity()
                                + " | lockedSpeed="
                                + batch
                                .getConfiguration()
                                .productionSpeed()
                                + " | lockedPMs="
                                + batch
                                .getConfiguration()
                                .productionMethodSelections()
                )
        );

        /*
         * Batch ACTIVE 后请求：
         *
         * installed 50 -> 20
         *
         * 但这只是 Deferred Operation，
         * 当前 Holding 不应该立即变化。
         */
        ProductionEquipmentOperationSubmission submission =
                context.getProductionEquipmentService()
                        .uninstall(
                                building.getId(),
                                30
                        );

        ProductionEquipmentHolding holding =
                context.getProductionEquipmentService()
                        .getHolding(
                                building.getId()
                        );

        int pending =
                context.getProductionEquipmentOperationQueue()
                        .getForBuilding(
                                building.getId()
                        )
                        .size();

        player.sendSystemMessage(
                Component.literal(
                        "[BATCH RECONFIG REQUEST] "
                                + "status="
                                + submission.status()
                                + " | holdingInstalled="
                                + holding.getInstalledQuantity()
                                + " | pending="
                                + pending
                )
        );

        /*
         * 再算一次 Live Staffing。
         *
         * 因为 uninstall(30) 仍然 pending，
         * 所以 Live capacity 应仍为 0.5。
         */
        StaffingSnapshot liveStaffing =
                context.getStaffingCalculator()
                        .calculate(
                                building
                        );

        player.sendSystemMessage(
                Component.literal(
                        "[LIVE AFTER QUEUED CHANGE] "
                                + "equipment="
                                + liveStaffing.equipmentCapacity()
                                + " | canStart="
                                + liveStaffing.canStart()
                                + " | speed="
                                + liveStaffing.productionSpeed()
                )
        );

        player.sendSystemMessage(
                Component.literal(
                        "[LOCKED BATCH] "
                                + "equipment="
                                + batch
                                .getConfiguration()
                                .equipmentCapacity()
                                + " | speed="
                                + batch
                                .getConfiguration()
                                .productionSpeed()
                )
        );
    }

    /**
     * 每次右键推进一次当前 ProductionBatch。
     *
     * 当前测试：
     *
     * locked productionSpeed = 0.5
     * fullSpeedProgressDelta = 0.2
     *
     * 所以实际：
     *
     * +0.1 / click
     *
     * 当 Batch 从 ACTIVE -> COMPLETED 时，
     * ProductionService 应自动：
     *
     * handleBatchBoundary()
     *      ↓
     * flush pending ProductionEquipment operations
     */
    private void runProductionBatchProgressTest(
            ServerEconomyContext context,
            EconomicBuilding building,
            Player player
    ) {
        ProductionBatch batch =
                building
                        .getProductionDepartment()
                        .getActiveBatch();

        if (batch == null) {

            player.sendSystemMessage(
                    Component.literal(
                            "[TEST ERROR] No production batch exists"
                    )
            );

            return;
        }

        if (!batch.isActive()) {

            player.sendSystemMessage(
                    Component.literal(
                            "[BATCH FINISHED] "
                                    + "status="
                                    + batch.getStatus()
                                    + " | progress="
                                    + batch.getProgress()
                    )
            );

            return;
        }

        double progressBefore =
                batch.getProgress();

        context.getProductionService()
                .advanceBatch(
                        building.getId(),
                        0.2
                );

        double progressAfter =
                batch.getProgress();

        player.sendSystemMessage(
                Component.literal(
                        "[BATCH PROGRESS] "
                                + "before="
                                + progressBefore
                                + " | lockedSpeed="
                                + batch
                                .getConfiguration()
                                .productionSpeed()
                                + " | fullSpeedDelta=0.2"
                                + " | after="
                                + progressAfter
                                + " | status="
                                + batch.getStatus()
                )
        );
    }
}