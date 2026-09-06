package com.bbmurloc.victoriaeconomics.common.registry;

import com.bbmurloc.victoriaeconomics.common.blockentity.BuildingAnchorBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

import static com.bbmurloc.victoriaeconomics.VictoriaEconomics.MODID;
import static com.bbmurloc.victoriaeconomics.common.registry.ModBlocks.BUILDING_ANCHOR_BLOCK;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final Supplier<BlockEntityType<BuildingAnchorBlockEntity>> BUILDING_ANCHOR_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register(
                   "building_anchor_block_entity" ,
                    () -> BlockEntityType.Builder.of(
                                    // The supplier to use for constructing the block entity instances.
                                    BuildingAnchorBlockEntity::new,
                                    // A vararg of blocks that can have this block entity.
                                    // This assumes the existence of the referenced blocks as DeferredBlock<Block>s.
                                    BUILDING_ANCHOR_BLOCK.get()
                            )
                            // Build using null; vanilla does some datafixer shenanigans with the parameter that we don't need.
                            .build(null)
            );

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITY_TYPES.register(eventBus);
    }
}
