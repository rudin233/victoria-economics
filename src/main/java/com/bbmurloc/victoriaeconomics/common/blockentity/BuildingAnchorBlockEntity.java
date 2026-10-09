package com.bbmurloc.victoriaeconomics.common.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

import static com.bbmurloc.victoriaeconomics.common.registry.ModBlockEntities.BUILDING_ANCHOR_BLOCK_ENTITY;

public class BuildingAnchorBlockEntity extends BlockEntity {
    private UUID buildingId;

    public BuildingAnchorBlockEntity(BlockPos pos, BlockState state) {
        super(BUILDING_ANCHOR_BLOCK_ENTITY.get(), pos, state);
    }


    public UUID getBuildingId() {
        return buildingId;
    }

    public void setBuildingId(UUID buildingId) {
        this.buildingId = buildingId;
        setChanged();
    }

    @Override
    protected void saveAdditional(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        super.saveAdditional(tag, registries);

        if (buildingId != null) {
            tag.putUUID("building_id", buildingId);
        }
    }

    @Override
    protected void loadAdditional(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        super.loadAdditional(tag, registries);

        if (tag.hasUUID("building_id")) {
            this.buildingId = tag.getUUID("building_id");
        } else {
            this.buildingId = null;
        }
    }
}
