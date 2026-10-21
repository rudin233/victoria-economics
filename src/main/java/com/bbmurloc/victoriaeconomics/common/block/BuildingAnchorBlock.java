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
                player.sendSystemMessage(Component.translatable("message.victoria_economics.anchor.unbound"));
            } else {
                var economy=ServerEconomyRuntime.get(serverLevel.getServer());
                var building = economy.getBuildingRegistry().get(anchor.getBuildingId());
                player.sendSystemMessage(building==null?Component.translatable("message.victoria_economics.anchor.missing"):
                        Component.translatable("message.victoria_economics.anchor.status",building.getId().toString(),building.getStatus().name(),building.lifecycle().name(),economy.getLifecycleService().forBuilding(building.getId()).toString()));
            }
        }
        return InteractionResult.CONSUME;
    }
}
