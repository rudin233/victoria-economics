package com.bbmurloc.victoriaeconomics.server.command;

import com.bbmurloc.victoriaeconomics.common.blockentity.BuildingAnchorBlockEntity;
import com.bbmurloc.victoriaeconomics.server.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.*;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.*;
import java.util.function.Consumer;

import static net.minecraft.commands.Commands.*;

/**
 * Server-only administration/debug entry. Production progress always comes from server ticks.
 */
public final class ProductionCommands {
    private ProductionCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(literal("ve").requires(source -> source.hasPermission(2))
                .then(literal("production")
                        .then(literal("list").executes(ctx -> run(ctx, economy -> reply(ctx, economy.getBuildingRegistry().getAll().stream().map(b -> b.getId() + " " + b.getBuildingTypeId()).toList().toString()))))
                        .then(argument("building", StringArgumentType.word())
                                .then(literal("status").executes(ctx -> run(ctx, economy -> status(ctx, economy))))
                                .then(literal("start").requires(source -> source.hasPermission(4)).executes(ctx -> run(ctx, economy -> {
                                    var batch = economy.getProductionService().startBatch(id(ctx));
                                    reply(ctx, "Started " + batch.getId());
                                })))
                                .then(literal("stop").requires(source -> source.hasPermission(4)).executes(ctx -> run(ctx, economy -> economy.getProductionService().abortBatch(id(ctx)))))
                                .then(literal("retry").requires(source -> source.hasPermission(4)).executes(ctx -> run(ctx, economy -> economy.getProductionService().retry(id(ctx)))))
                                .then(literal("automatic").requires(source -> source.hasPermission(4))
                                        .then(argument("enabled", BoolArgumentType.bool()).executes(ctx -> run(ctx, economy -> economy.getProductionService().setAutomatic(id(ctx), BoolArgumentType.getBool(ctx, "enabled"))))))
                                .then(literal("pm").requires(source -> source.hasPermission(4))
                                        .then(argument("group", StringArgumentType.word()).then(argument("method", StringArgumentType.word())
                                                .executes(ctx -> run(ctx, economy -> economy.getBuildingService().selectProductionMethod(id(ctx), StringArgumentType.getString(ctx, "group"), StringArgumentType.getString(ctx, "method")))))))
                                .then(literal("cancel_pm").requires(source -> source.hasPermission(4)).executes(ctx -> run(ctx, economy -> economy.getBuildingService().cancelPendingProductionMethods(id(ctx)))))
                                .then(literal("install").requires(source -> source.hasPermission(4)).then(argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> run(ctx, economy -> reply(ctx, economy.getProductionEquipmentService().install(id(ctx), IntegerArgumentType.getInteger(ctx, "amount")).toString())))))
                                .then(literal("uninstall").requires(source -> source.hasPermission(4)).then(argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> run(ctx, economy -> reply(ctx, economy.getProductionEquipmentService().uninstall(id(ctx), IntegerArgumentType.getInteger(ctx, "amount")).toString())))))
                                .then(literal("cancel_equipment").requires(source -> source.hasPermission(4)).then(argument("request", StringArgumentType.word())
                                        .executes(ctx -> run(ctx, economy -> reply(ctx, "Cancelled: " + economy.getProductionEquipmentService().cancel(id(ctx), UUID.fromString(StringArgumentType.getString(ctx, "request"))))))))))
                .then(literal("debug").requires(source -> source.hasPermission(4))
                        .then(literal("create").then(argument("type", StringArgumentType.word()).then(argument("capacity", DoubleArgumentType.doubleArg(0))
                                .executes(ctx -> run(ctx, economy -> {
                                    var building = economy.getBuildingService().createBuilding(StringArgumentType.getString(ctx, "type"));
                                    reply(ctx, "Created building " + building.getId());
                                    economy.getInventoryStore().createLocation(building.getId(), DoubleArgumentType.getDouble(ctx, "capacity"));
                                })))))
                        .then(argument("building", StringArgumentType.word())
                                .then(literal("configure_storage").then(argument("capacity", DoubleArgumentType.doubleArg(0))
                                        .executes(ctx -> run(ctx, economy -> {
                                            if (economy.getBuildingRegistry().get(id(ctx)) == null)
                                                throw new IllegalArgumentException("Unknown building");
                                            economy.getInventoryStore().createLocation(id(ctx), DoubleArgumentType.getDouble(ctx, "capacity"));
                                        }))))
                                .then(literal("deposit").then(argument("good", StringArgumentType.word()).then(argument("quantity", DoubleArgumentType.doubleArg(0))
                                        .executes(ctx -> run(ctx, economy -> {
                                            String good = StringArgumentType.getString(ctx, "good");
                                            if (!economy.getGoodRegistry().contains(good))
                                                throw new IllegalArgumentException("Unknown good: " + good);
                                            economy.getInventoryStore().deposit(id(ctx), Map.of(good, DoubleArgumentType.getDouble(ctx, "quantity")));
                                        })))))
                                .then(literal("withdraw").then(argument("good", StringArgumentType.word()).then(argument("quantity", DoubleArgumentType.doubleArg(0))
                                        .executes(ctx -> run(ctx, economy -> economy.getInventoryStore().withdraw(id(ctx),
                                                Map.of(StringArgumentType.getString(ctx, "good"), DoubleArgumentType.getDouble(ctx, "quantity"))))))))
                                .then(literal("add_equipment").then(argument("amount", IntegerArgumentType.integer(1)).executes(ctx -> run(ctx, economy ->
                                        economy.getProductionEquipmentService().addUninstalledEquipment(id(ctx), IntegerArgumentType.getInteger(ctx, "amount"))))))
                                .then(literal("bind_anchor").then(argument("position", BlockPosArgument.blockPos()).executes(ctx -> run(ctx, economy -> {
                                    if (economy.getBuildingRegistry().get(id(ctx)) == null)
                                        throw new IllegalArgumentException("Unknown building");
                                    try {
                                        var block = ctx.getSource().getLevel().getBlockEntity(BlockPosArgument.getLoadedBlockPos(ctx, "position"));
                                        if (!(block instanceof BuildingAnchorBlockEntity anchor))
                                            throw new IllegalArgumentException("Position is not a building anchor");
                                        if (anchor.getBuildingId() != null && !anchor.getBuildingId().equals(id(ctx)))
                                            throw new IllegalStateException("Anchor already bound");
                                        anchor.setBuildingId(id(ctx));
                                    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                                        throw new IllegalArgumentException(e.getMessage(), e);
                                    }
                                })))))));
    }

    private static UUID id(CommandContext<CommandSourceStack> ctx) {
        return UUID.fromString(StringArgumentType.getString(ctx, "building"));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Consumer<ServerEconomyContext> action) {
        try {
            action.accept(ServerEconomyRuntime.get(ctx.getSource().getServer()));
            return 1;
        } catch (RuntimeException failure) {
            ctx.getSource().sendFailure(Component.literal("VE: " + failure.getMessage()));
            return 0;
        }
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
    }

    private static void status(CommandContext<CommandSourceStack> ctx, ServerEconomyContext economy) {
        var building = economy.getBuildingRegistry().get(id(ctx));
        if (building == null) throw new IllegalArgumentException("Unknown building");
        var department = building.getProductionDepartment();
        var execution = department.getExecution();
        var batch = execution.batch() == null ? execution.lastBatch() : execution.batch();
        reply(ctx, "Building " + id(ctx) + " " + building.getStatus() + "; PM=" + department.getSelectedProductionMethods()
                + "; pending=" + department.getPendingProductionMethods() + "; boundary=" + execution.boundary()
                + "; batch=" + (batch == null ? "none" : batch.getId() + " " + batch.getStatus() + " " + batch.getProgress())
                + "; blocked=" + economy.getProductionService().blockedReason(id(ctx)));
        reply(ctx, "Equipment " + economy.getProductionEquipmentService().getHolding(id(ctx)).state());
        reply(ctx, "Inventory " + economy.getInventoryStore().inspect(id(ctx)));
    }
}
