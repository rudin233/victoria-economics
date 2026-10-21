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
import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation;
import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import com.mojang.brigadier.CommandDispatcher;

import static net.minecraft.commands.Commands.*;

/**
 * Server-only administration/debug entry. Production progress always comes from server ticks.
 */
public final class ProductionCommands {
    private ProductionCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("ve").requires(source -> source.hasPermission(2))
                .then(literal("building").then(argument("building",StringArgumentType.word())
                        .then(literal("lifecycle")
                                .then(literal("status").executes(ctx->run(ctx,economy->reply(ctx,economy.getLifecycleService().forBuilding(id(ctx)).toString()))))
                                .then(literal("sale").requires(source->source.hasPermission(4))
                                        .then(argument("operation",StringArgumentType.word()).then(argument("seller",StringArgumentType.word()).then(argument("buyer",StringArgumentType.word()).then(argument("revision",LongArgumentType.longArg(0))
                                                .executes(ctx->run(ctx,economy->reply(ctx,economy.getLifecycleService().request(lifecycleCommand(ctx,BuildingLifecycleOperation.Kind.SALE)).toString()))))))))
                                .then(literal("demolish").requires(source->source.hasPermission(4))
                                        .then(argument("operation",StringArgumentType.word()).then(argument("seller",StringArgumentType.word()).then(argument("revision",LongArgumentType.longArg(0))
                                                .executes(ctx->run(ctx,economy->reply(ctx,economy.getLifecycleService().request(lifecycleCommand(ctx,BuildingLifecycleOperation.Kind.DEMOLITION)).toString())))))))
                                .then(literal("retry").requires(source->source.hasPermission(4)).then(argument("operation",StringArgumentType.word())
                                        .executes(ctx->run(ctx,economy->{var op=economy.getLifecycleService().find(operation(ctx)).orElseThrow();if(!op.command().buildingId().equals(id(ctx)))throw new IllegalArgumentException("Operation belongs to another building");reply(ctx,economy.getLifecycleService().recover(operation(ctx)).toString());}))))
                                .then(literal("verify").requires(source->source.hasPermission(4)).then(argument("operation",StringArgumentType.word())
                                        .executes(ctx->run(ctx,economy->reply(ctx,economy.getLifecycleService().verify(operation(ctx),id(ctx)).toString()))))))))
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
                                .then(literal("pause").requires(source->source.hasPermission(4))
                                        .then(argument("enabled",BoolArgumentType.bool()).executes(ctx->run(ctx,economy->economy.getProductionService().setManualPause(id(ctx),BoolArgumentType.getBool(ctx,"enabled"))))))
                                .then(literal("staffing").requires(source->source.hasPermission(4))
                                        .then(argument("occupation",StringArgumentType.word()).then(argument("target",IntegerArgumentType.integer(0))
                                                .executes(ctx->run(ctx,economy->economy.getStaffingService().requestTarget(id(ctx),StringArgumentType.getString(ctx,"occupation"),IntegerArgumentType.getInteger(ctx,"target")))))))
                                .then(literal("cancel_staffing").requires(source->source.hasPermission(4))
                                        .then(argument("occupation",StringArgumentType.word()).executes(ctx->run(ctx,economy->economy.getStaffingService().cancelPending(id(ctx),StringArgumentType.getString(ctx,"occupation"))))))
                                .then(literal("pm").requires(source -> source.hasPermission(4))
                                        .then(argument("group", StringArgumentType.word()).then(argument("method", StringArgumentType.word())
                                                .executes(ctx -> run(ctx, economy -> economy.getProductionService().selectProductionMethod(id(ctx), StringArgumentType.getString(ctx, "group"), StringArgumentType.getString(ctx, "method")))))))
                                .then(literal("cancel_pm").requires(source -> source.hasPermission(4)).executes(ctx -> run(ctx, economy -> economy.getProductionService().cancelPendingProductionMethods(id(ctx)))))))
                .then(literal("debug").requires(source -> source.hasPermission(4))
                        .then(literal("create").then(argument("type", StringArgumentType.word()).then(argument("capacity", LongArgumentType.longArg(0))
                                .executes(ctx -> run(ctx, economy -> {
                                    var building = economy.getBuildingService().createBuilding(StringArgumentType.getString(ctx, "type"));
                                    economy.getInventoryStore().createLocation(building.getId(), LongArgumentType.getLong(ctx, "capacity"));
                                    reply(ctx, "Created building " + building.getId());
                                }))
                                .then(argument("company",StringArgumentType.word()).then(argument("region",StringArgumentType.word())
                                        .executes(ctx->run(ctx,economy->{
                                            var b=economy.getBuildingService().createBuilding(StringArgumentType.getString(ctx,"type"),CompanyId.parse(StringArgumentType.getString(ctx,"company")),RegionId.parse(StringArgumentType.getString(ctx,"region")));
                                            economy.getInventoryStore().createLocation(b.getId(),LongArgumentType.getLong(ctx,"capacity"));reply(ctx,"Created building "+b.getId());
                                        })))))))
                        .then(argument("building", StringArgumentType.word())
                                .then(literal("configure_storage").then(argument("capacity", LongArgumentType.longArg(0))
                                        .executes(ctx -> run(ctx, economy -> {
                                            if (economy.getBuildingRegistry().get(id(ctx)) == null)
                                                throw new IllegalArgumentException("Unknown building");
                                            economy.getInventoryStore().createLocation(id(ctx), LongArgumentType.getLong(ctx, "capacity"));
                                        }))))
                                .then(literal("deposit").then(argument("good", StringArgumentType.word()).then(argument("quantity", LongArgumentType.longArg(0))
                                        .executes(ctx -> run(ctx, economy -> {
                                            String good = StringArgumentType.getString(ctx, "good");
                                            if (!economy.getGoodRegistry().contains(good))
                                                throw new IllegalArgumentException("Unknown good: " + good);
                                            economy.getInventoryStore().deposit(id(ctx), Map.of(good, LongArgumentType.getLong(ctx, "quantity")));
                                        })))))
                                .then(literal("withdraw").then(argument("good", StringArgumentType.word()).then(argument("quantity", LongArgumentType.longArg(0))
                                        .executes(ctx -> run(ctx, economy -> economy.getInventoryStore().withdraw(id(ctx),
                                                Map.of(StringArgumentType.getString(ctx, "good"), LongArgumentType.getLong(ctx, "quantity"))))))))
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
    private static UUID operation(CommandContext<CommandSourceStack> ctx) {return UUID.fromString(StringArgumentType.getString(ctx,"operation"));}
    private static BuildingLifecycleOperation.Command lifecycleCommand(CommandContext<CommandSourceStack> ctx,BuildingLifecycleOperation.Kind kind) {
        return new BuildingLifecycleOperation.Command(operation(ctx),id(ctx),CompanyId.parse(StringArgumentType.getString(ctx,"seller")),
                kind==BuildingLifecycleOperation.Kind.SALE?CompanyId.parse(StringArgumentType.getString(ctx,"buyer")):null,kind,LongArgumentType.getLong(ctx,"revision"));
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
        var configuration = economy.getProductionService().methods(id(ctx));
        var execution = economy.getProductionService().execution(id(ctx));
        var batch = execution.batch() == null ? execution.lastBatch() : execution.batch();
        reply(ctx, "Building " + id(ctx) + " " + building.getStatus() + "; lifecycle="+building.lifecycle()+"; owner="+building.ownerCompanyId()+"; region="+building.regionId()+"; operation="+economy.getLifecycleService().forBuilding(id(ctx))+"; PM=" + configuration.effective().methods()
                + "; configRevision=" + configuration.configurationRevision() + "; effectiveRevision=" + configuration.effectiveRevision()
                + "; executionRevision=" + execution.executionRevision() + "; completedTick=" + execution.lastCompletedTick()
                + "; pending=" + configuration.pending() + "; boundary=" + execution.boundary()
                + "; processingTick="+execution.processingTick()+"; pauses="+execution.pauseReasons()
                + "; batch=" + (batch == null ? "none" : batch.getId() + " " + batch.getStatus() + " " + batch.getProgress())
                + "; blocked=" + economy.getProductionService().blockedReason(id(ctx)));
        reply(ctx, "Inventory " + economy.getInventoryStore().inspect(id(ctx)));
        reply(ctx,"Staffing "+economy.getStaffingCalculator().calculate(id(ctx))+"; payroll="+economy.getPayrollQuery().state(id(ctx)));
    }
}
