package com.bbmurloc.victoriaeconomics.common.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import static com.bbmurloc.victoriaeconomics.VictoriaEconomics.MODID;
import static com.bbmurloc.victoriaeconomics.common.registry.ModItems.EXAMPLE_ITEM;

public class ModCreativeModeTabs {
    // Create a Deferred Register to hold creative tabs in this mod's namespace.
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Creates a creative tab with the id "victoria_economics:example_tab" after the combat tab.
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.victoria_economics")) //The language key for the title of your CreativeModeTab
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                // 使用反射遍历 ModItems 类中所有 DeferredItem 字段
                try {
                    for (var field : ModItems.class.getDeclaredFields()) {
                        // 只处理 DeferredItem 类型的字段
                        if (DeferredItem.class.isAssignableFrom(field.getType())) {
                            // 因为是 public static，直接 get(null) 获取值
                            @SuppressWarnings("unchecked")
                            DeferredItem<?> item = (DeferredItem<?>) field.get(null);
                            // Add the example item to the tab. For your own tabs, this method is preferred over the event
                            output.accept(item.get());
                        }
                    }
                    // 2. 扫描 ModBlocks（新增）
                    for (var field : ModBlocks.class.getDeclaredFields()) {
                        if (DeferredItem.class.isAssignableFrom(field.getType())) {
                            @SuppressWarnings("unchecked")
                            DeferredItem<?> item = (DeferredItem<?>) field.get(null);
                            output.accept(item.get());
                        }
                    }
                } catch (IllegalAccessException e) {
                    // 理论上不会发生，因为所有字段都是 public
                    throw new RuntimeException("Failed to add items to creative tab", e);
                }
            }).build());


    public static void register(IEventBus eventBus) {
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(eventBus);

    }

}
