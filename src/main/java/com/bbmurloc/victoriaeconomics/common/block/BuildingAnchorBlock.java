package com.bbmurloc.victoriaeconomics.common.block;

import com.bbmurloc.victoriaeconomics.common.blockentity.BuildingAnchorBlockEntity;
import com.bbmurloc.victoriaeconomics.server.ServerEconomyContext;
import com.bbmurloc.victoriaeconomics.server.ServerEconomyRuntime;
import com.bbmurloc.victoriaeconomics.server.building.EconomicBuilding;
import com.bbmurloc.victoriaeconomics.server.production.ResolvedProductionRecipe;
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

public class BuildingAnchorBlock extends Block implements EntityBlock {


    public BuildingAnchorBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BuildingAnchorBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hitResult
    ) {
        if (!level.isClientSide
                && level instanceof ServerLevel serverLevel) {

            BlockEntity blockEntity = level.getBlockEntity(pos);

            if (blockEntity instanceof BuildingAnchorBlockEntity anchor) {

                MinecraftServer server = serverLevel.getServer();

                ServerEconomyContext context =
                        ServerEconomyRuntime.get(server);

                if (anchor.getBuildingId() == null) {

                    EconomicBuilding building =
                            context.getBuildingService()
                                    .createBuilding("tooling_workshop");

                    anchor.setBuildingId(building.getId());

                    player.displayClientMessage(
                            Component.literal(
                                    "Created building: " + building.getId()
                            ),
                            false
                    );
                }
                else {
                    EconomicBuilding building =
                            context.getBuildingRegistry()
                                    .get(anchor.getBuildingId());

                    if (building != null) {
//                        context.getBuildingService()
//                                .stopBuilding(building.getId());

                        player.displayClientMessage(
                                Component.literal(
                                        "Found building: "
                                                + building.getId()
                                                + " | type = "
                                                + building.getBuildingTypeId()
                                                + " | status = "
                                                + building.getStatus()
                                ),
                                false
                        );

                        //        测试生产配方
                        ResolvedProductionRecipe recipe =
                                context.getProductionRecipeResolver()
                                        .resolve(building);

                        player.displayClientMessage(
                                Component.literal(
                                        "Recipe = " + recipe
                                ),
                                false
                        );

                    } else {
                        player.displayClientMessage(
                                Component.literal(
                                        "Building not found: "
                                                + anchor.getBuildingId()
                                ),
                                false
                        );
                    }
                }
            }
        }



        return InteractionResult.sidedSuccess(level.isClientSide);
    }

}
