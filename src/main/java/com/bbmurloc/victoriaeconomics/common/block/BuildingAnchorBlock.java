package com.bbmurloc.victoriaeconomics.common.block;

import com.bbmurloc.victoriaeconomics.common.blockentity.BuildingAnchorBlockEntity;
import com.bbmurloc.victoriaeconomics.server.ServerEconomyRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof BuildingAnchorBlockEntity anchor) {
            if (anchor.getBuildingId() == null) {
                player.sendSystemMessage(Component.literal("建筑锚点尚未绑定。"));
            } else {
                var building = ServerEconomyRuntime.get(serverLevel.getServer()).getBuildingRegistry().get(anchor.getBuildingId());
                player.sendSystemMessage(Component.literal(building == null ? "未找到锚点绑定的经济建筑。" :
                        "建筑 " + building.getId() + " / " + building.getStatus()));
            }
        }
        return InteractionResult.CONSUME;
    }
}
