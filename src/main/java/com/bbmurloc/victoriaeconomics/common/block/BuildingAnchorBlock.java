package com.bbmurloc.victoriaeconomics.common.block;

import com.bbmurloc.victoriaeconomics.common.blockentity.BuildingAnchorBlockEntity;
import com.bbmurloc.victoriaeconomics.server.ServerEconomyContext;
import com.bbmurloc.victoriaeconomics.server.ServerEconomyRuntime;
import com.bbmurloc.victoriaeconomics.server.building.EconomicBuilding;
import com.bbmurloc.victoriaeconomics.server.production.ResolvedProductionRecipe;
import com.bbmurloc.victoriaeconomics.server.staffing.StaffingSnapshot;

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
                 *
                 * 第一次右键创建测试建筑。
                 */
                if (anchor.getBuildingId() == null) {

                    createTestBuilding(
                            context,
                            anchor,
                            player
                    );

                } else {

                    /*
                     * Anchor 已经有 buildingId：
                     *
                     * 尝试从运行时 BuildingRegistry
                     * 找到对应 EconomicBuilding。
                     */
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
                     * 临时手动测试入口。
                     *
                     * 后续正式 GUI / debug command 完成后，
                     * 这些测试代码应从 Anchor 中移除。
                     */
                    runStaffingTest(
                            context,
                            building,
                            player
                    );
                    testEquipmentHiringLimit(
                            context,
                            building,
                            player
                    );
                    testStaffingPlan(
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
     * 显示当前 Anchor 对应的建筑基本信息。
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
    // Temporary Staffing manual tests
    // =========================================================

    /**
     * Staffing 手动测试的总入口。
     *
     * 当前测试顺序：
     *
     * 1. 设置测试设备数量
     * 2. 显示当前解析后的生产配方
     * 3. 为这栋建筑创建测试员工
     * 4. 计算并显示 StaffingSnapshot
     */
    private void runStaffingTest(
            ServerEconomyContext context,
            EconomicBuilding building,
            Player player
    ) {
        setTestEquipment(
                context,
                building
        );

        showResolvedRecipe(
                context,
                building,
                player
        );

        ensureTestEmployees(
                context,
                building
        );

        showStaffing(
                context,
                building,
                player
        );
    }

    /**
     * Step 1:
     *
     * 临时把当前设备数量设成 50。
     *
     * 如果 BuildingType.maxEquipment = 100，
     * 则：
     *
     * equipmentCapacity = 0.5
     */
    private void setTestEquipment(
            ServerEconomyContext context,
            EconomicBuilding building
    ) {
        context.getBuildingService()
                .setCurrentEquipment(
                        building.getId(),
                        50
                );
    }

    /**
     * Step 2:
     *
     * 显示当前 PM 组合解析得到的最终生产配方。
     */
    private void showResolvedRecipe(
            ServerEconomyContext context,
            EconomicBuilding building,
            Player player
    ) {
        ResolvedProductionRecipe recipe =
                context.getProductionRecipeResolver()
                        .resolve(building);

        player.sendSystemMessage(
                Component.literal(
                        "Recipe = "
                                + recipe
                )
        );
    }

    /**
     * Step 3:
     *
     * 为当前建筑准备测试员工，
     * 并把人数补充到当前测试场景需要的数量。
     *
     * 当前测试目标：
     *
     * laborer   = 5
     * machinist = 2
     *
     * 这些人数正好对应：
     *
     * required laborer   = 10
     * required machinist = 4
     *
     * equipmentCapacity = 0.5
     *
     * 因此设备允许的最大人数分别为：
     *
     * laborer   = floor(10 * 0.5) = 5
     * machinist = floor(4 * 0.5)  = 2
     *
     * 每个测试员工 UUID 都根据：
     *
     * buildingId + occupationId + index
     *
     * 稳定生成。
     *
     * 因此：
     * 1. 同一栋建筑重复右键不会重复创建同一个测试员工；
     * 2. 同一职业可以生成多个不同测试员工；
     * 3. 不同建筑会生成不同的测试员工 UUID；
     * 4. 不会发生两个测试建筑争用同一个固定测试员工的问题。
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
     * 确保某栋建筑拥有指定数量的某职业测试员工。
     *
     * 例如：
     *
     * occupationId = "laborer"
     * count = 5
     *
     * 会依次生成：
     *
     * laborer #1
     * laborer #2
     * laborer #3
     * laborer #4
     * laborer #5
     *
     * 对每一个生成的 employeeId：
     *
     * 如果已经存在 EmploymentRecord，
     * 则跳过；
     *
     * 如果尚未就业，
     * 则通过 EmploymentService.hire()
     * 建立正式的测试雇佣关系。
     *
     * 注意：
     * 这里仍然经过 EmploymentService，
     * 因此测试员工必须满足真实的招聘规则，
     * 包括职业需求和设备人数上限。
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
                    .isEmployed(employeeId)) {

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
     * 根据建筑 ID、职业 ID 和序号，
     * 生成一个稳定且可重复的测试员工 UUID。
     *
     * 输入例如：
     *
     * buildingId   = Building A
     * occupationId = "laborer"
     * index        = 1
     *
     * 用于生成字符串：
     *
     * BuildingA:test:laborer:1
     *
     * 再通过 UUID.nameUUIDFromBytes(...)
     * 得到稳定 UUID。
     *
     * 因此：
     *
     * Building A + laborer + 1
     *      -> UUID A-L1
     *
     * Building A + laborer + 2
     *      -> UUID A-L2
     *
     * Building A + machinist + 1
     *      -> UUID A-M1
     *
     * Building B + laborer + 1
     *      -> UUID B-L1
     *
     * 对相同的 buildingId、occupationId 和 index，
     * 每次调用都会得到相同 UUID；
     *
     * 只要其中任意一项不同，
     * 就会得到不同 UUID。
     *
     * 这样既能避免重复右键不断产生新员工，
     * 又能让同一栋建筑拥有多个同职业测试员工。
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
     * Step 4:
     *
     * 根据：
     *
     * Recipe
     * StaffingPlan
     * Employment
     * Equipment
     *
     * 计算当前 StaffingSnapshot。
     */
    private void showStaffing(
            ServerEconomyContext context,
            EconomicBuilding building,
            Player player
    ) {
        StaffingSnapshot staffing =
                context.getStaffingCalculator()
                        .calculate(building);

        player.sendSystemMessage(
                Component.literal(
                        "Staffing = "
                                + staffing
                )
        );
    }

    private void testEquipmentHiringLimit(
            ServerEconomyContext context,
            EconomicBuilding building,
            Player player
    ) {
        UUID sixthLaborer =
                createTestEmployeeId(
                        building.getId(),
                        "laborer",
                        6
                );

        if (context.getEmploymentService()
                .isEmployed(sixthLaborer)) {
            return;
        }

        try {
            context.getEmploymentService()
                    .hire(
                            sixthLaborer,
                            building.getId(),
                            "laborer"
                    );

            player.sendSystemMessage(
                    Component.literal(
                            "[TEST ERROR] Sixth laborer was hired"
                    )
            );

        } catch (IllegalStateException e) {

            player.sendSystemMessage(
                    Component.literal(
                            "[TEST PASS] Equipment limit blocked sixth laborer: "
                                    + e.getMessage()
                    )
            );
        }
    }

    private void testStaffingPlan(
            ServerEconomyContext context,
            EconomicBuilding building,
            Player player
    ) {
        building
                .getHumanResourcesDepartment()
                .setTargetRatio(
                        "laborer",
                        0.3
                );

        StaffingSnapshot staffing =
                context.getStaffingCalculator()
                        .calculate(building);

        var laborer =
                staffing.occupations()
                        .get("laborer");

        player.sendSystemMessage(
                Component.literal(
                        "HR Plan Test: "
                                + "targetRatio="
                                + laborer.targetRatio()
                                + ", targetWorkers="
                                + laborer.targetWorkers()
                                + ", hiringTarget="
                                + laborer.hiringTargetWorkers()
                                + ", employed="
                                + laborer.employedWorkers()
                                + ", effective="
                                + laborer.effectiveWorkers()
                                + ", staffingRatio="
                                + laborer.staffingRatio()
                                + ", speed="
                                + staffing.productionSpeed()
                )
        );
    }
}