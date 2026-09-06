package com.bbmurloc.victoriaeconomics.common.registry;

import com.bbmurloc.victoriaeconomics.common.block.BuildingAnchorBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import static com.bbmurloc.victoriaeconomics.VictoriaEconomics.MODID;

public class ModBlocks {
    // Create a Deferred Register to hold blocks in this mod's namespace.
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);

    // Creates a new block with the id "victoria_economics:example_block".
    public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block", BlockBehaviour.Properties.of().mapColor(MapColor.STONE));



    public static final DeferredBlock<Block> BUILDING_ANCHOR_BLOCK =
            BLOCKS.registerBlock(
                    "building_anchor_block",
                    BuildingAnchorBlock::new,
                    BlockBehaviour.Properties.of().mapColor(MapColor.STONE));
    //注册方法
    public static void register(IEventBus eventBus){
        BLOCKS.register(eventBus);
    }
}
