package com.bbmurloc.victoriaeconomics.common.registry;

import com.bbmurloc.victoriaeconomics.VictoriaEconomics;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import static com.bbmurloc.victoriaeconomics.common.registry.ModBlocks.BUILDING_ANCHOR_BLOCK;
import static com.bbmurloc.victoriaeconomics.common.registry.ModBlocks.EXAMPLE_BLOCK;

public class ModItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(VictoriaEconomics.MODID);
    //商品表中,英文merchant_ships和中文表中自己产出缺少对应item

    //测试用item
    // Creates a test food item with the id "victoria_economics:example_item".
    public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item", new Item.Properties().food(new FoodProperties.Builder()
            .alwaysEdible().nutrition(1).saturationModifier(2f).build()));
    // -------- MILITARY (军事) --------
    public static final DeferredItem<Item> AMMUNITION =
            ITEMS.register("ammunition", () -> new Item(new Item.Properties())); // 弹药
    public static final DeferredItem<Item> SMALL_ARMS =
            ITEMS.register("small_arms", () -> new Item(new Item.Properties())); // 轻武器
    public static final DeferredItem<Item> ARTILLERY =
            ITEMS.register("artillery", () -> new Item(new Item.Properties())); // 火炮
    public static final DeferredItem<Item> TANKS =
            ITEMS.register("tanks", () -> new Item(new Item.Properties())); // 坦克
    public static final DeferredItem<Item> AEROPLANES =
            ITEMS.register("aeroplanes", () -> new Item(new Item.Properties())); // 飞机
    public static final DeferredItem<Item> MANOWARS =
            ITEMS.register("manowars", () -> new Item(new Item.Properties())); // 风帆战舰
    public static final DeferredItem<Item> IRONCLADS =
            ITEMS.register("ironclads", () -> new Item(new Item.Properties())); // 铁甲舰

    // -------- STAPLE (基本生活用品) --------
    public static final DeferredItem<Item> GRAIN =
            ITEMS.register("grain", () -> new Item(new Item.Properties())); // 谷物
    public static final DeferredItem<Item> FISH =
            ITEMS.register("fish", () -> new Item(new Item.Properties())); // 鱼
    public static final DeferredItem<Item> FABRIC =
            ITEMS.register("fabric", () -> new Item(new Item.Properties())); // 织物
    public static final DeferredItem<Item> WOOD =
            ITEMS.register("wood", () -> new Item(new Item.Properties())); // 木材
    public static final DeferredItem<Item> GROCERIES =
            ITEMS.register("groceries", () -> new Item(new Item.Properties())); // 加工食品
    public static final DeferredItem<Item> CLOTHES =
            ITEMS.register("clothes", () -> new Item(new Item.Properties())); // 衣物
    public static final DeferredItem<Item> FURNITURE =
            ITEMS.register("furniture", () -> new Item(new Item.Properties())); // 家具
    public static final DeferredItem<Item> PAPER =
            ITEMS.register("paper", () -> new Item(new Item.Properties())); // 纸张
    public static final DeferredItem<Item> SERVICES =
            ITEMS.register("services", () -> new Item(new Item.Properties())); // 服务
    public static final DeferredItem<Item> TRANSPORTATION =
            ITEMS.register("transportation", () -> new Item(new Item.Properties())); // 运力
    public static final DeferredItem<Item> ELECTRICITY =
            ITEMS.register("electricity", () -> new Item(new Item.Properties())); // 电力

    // -------- INDUSTRIAL (工业品) --------
    public static final DeferredItem<Item> CLIPPERS =
            ITEMS.register("clippers", () -> new Item(new Item.Properties())); // 帆船
    public static final DeferredItem<Item> STEAMERS =
            ITEMS.register("steamers", () -> new Item(new Item.Properties())); // 蒸汽船
    public static final DeferredItem<Item> SILK =
            ITEMS.register("silk", () -> new Item(new Item.Properties())); // 丝绸
    public static final DeferredItem<Item> DYE =
            ITEMS.register("dye", () -> new Item(new Item.Properties())); // 染料
    public static final DeferredItem<Item> SULFUR =
            ITEMS.register("sulfur", () -> new Item(new Item.Properties())); // 硫磺
    public static final DeferredItem<Item> COAL =
            ITEMS.register("coal", () -> new Item(new Item.Properties())); // 煤
    public static final DeferredItem<Item> IRON =
            ITEMS.register("iron", () -> new Item(new Item.Properties())); // 铁
    public static final DeferredItem<Item> LEAD =
            ITEMS.register("lead", () -> new Item(new Item.Properties())); // 铅
    public static final DeferredItem<Item> HARDWOOD =
            ITEMS.register("hardwood", () -> new Item(new Item.Properties())); // 硬木
    public static final DeferredItem<Item> RUBBER =
            ITEMS.register("rubber", () -> new Item(new Item.Properties())); // 橡胶
    public static final DeferredItem<Item> OIL =
            ITEMS.register("oil", () -> new Item(new Item.Properties())); // 油
    public static final DeferredItem<Item> ENGINES =
            ITEMS.register("engines", () -> new Item(new Item.Properties())); // 发动机
    public static final DeferredItem<Item> STEEL =
            ITEMS.register("steel", () -> new Item(new Item.Properties())); // 钢
    public static final DeferredItem<Item> GLASS =
            ITEMS.register("glass", () -> new Item(new Item.Properties())); // 玻璃
    public static final DeferredItem<Item> FERTILIZER =
            ITEMS.register("fertilizer", () -> new Item(new Item.Properties())); // 肥料
    public static final DeferredItem<Item> TOOLS =
            ITEMS.register("tools", () -> new Item(new Item.Properties())); // 工具
    public static final DeferredItem<Item> EXPLOSIVES =
            ITEMS.register("explosives", () -> new Item(new Item.Properties())); // 炸药

    // -------- LUXURY (奢侈品) --------
    public static final DeferredItem<Item> PORCELAIN =
            ITEMS.register("porcelain", () -> new Item(new Item.Properties())); // 瓷器
    public static final DeferredItem<Item> MEAT =
            ITEMS.register("meat", () -> new Item(new Item.Properties())); // 肉
    public static final DeferredItem<Item> FRUIT =
            ITEMS.register("fruit", () -> new Item(new Item.Properties())); // 水果
    public static final DeferredItem<Item> LIQUOR =
            ITEMS.register("liquor", () -> new Item(new Item.Properties())); // 烈酒
    public static final DeferredItem<Item> WINE =
            ITEMS.register("wine", () -> new Item(new Item.Properties())); // 葡萄酒
    public static final DeferredItem<Item> TEA =
            ITEMS.register("tea", () -> new Item(new Item.Properties())); // 茶叶
    public static final DeferredItem<Item> COFFEE =
            ITEMS.register("coffee", () -> new Item(new Item.Properties())); // 咖啡
    public static final DeferredItem<Item> SUGAR =
            ITEMS.register("sugar", () -> new Item(new Item.Properties())); // 糖
    public static final DeferredItem<Item> TOBACCO =
            ITEMS.register("tobacco", () -> new Item(new Item.Properties())); // 烟草
    public static final DeferredItem<Item> OPIUM =
            ITEMS.register("opium", () -> new Item(new Item.Properties())); // 鸦片
    public static final DeferredItem<Item> AUTOMOBILES =
            ITEMS.register("automobiles", () -> new Item(new Item.Properties())); // 汽车
    public static final DeferredItem<Item> TELEPHONES =
            ITEMS.register("telephones", () -> new Item(new Item.Properties())); // 电话机
    public static final DeferredItem<Item> RADIOS =
            ITEMS.register("radios", () -> new Item(new Item.Properties())); // 无线电
    public static final DeferredItem<Item> LUXURY_CLOTHES =
            ITEMS.register("luxury_clothes", () -> new Item(new Item.Properties())); // 高档衣物
    public static final DeferredItem<Item> LUXURY_FURNITURE =
            ITEMS.register("luxury_furniture", () -> new Item(new Item.Properties())); // 高档家具
    public static final DeferredItem<Item> GOLD =
            ITEMS.register("gold", () -> new Item(new Item.Properties())); // 黄金
    public static final DeferredItem<Item> FINE_ART =
            ITEMS.register("fine_art", () -> new Item(new Item.Properties())); // 艺术品

//    注册BlockItem
    // Creates a block item with the id "victoria_economics:example_block".
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ModItems.ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);

    public static final DeferredItem<BlockItem> BUILDING_ANCHOR_BLOCK_ITEM = ModItems.ITEMS.registerSimpleBlockItem("building_anchor_block_item", BUILDING_ANCHOR_BLOCK);
    // 注册方法
    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
