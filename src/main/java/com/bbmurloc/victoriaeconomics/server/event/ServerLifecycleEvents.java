package com.bbmurloc.victoriaeconomics.server.event;

import com.bbmurloc.victoriaeconomics.server.ServerEconomyRuntime;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import com.bbmurloc.victoriaeconomics.server.command.ProductionCommands;

public final class ServerLifecycleEvents {

    private ServerLifecycleEvents() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ServerLifecycleEvents::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(ServerLifecycleEvents::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerLifecycleEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(ProductionCommands::register);
    }

    private static void onServerAboutToStart(ServerAboutToStartEvent event) {
        ServerEconomyRuntime.start(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        ServerEconomyRuntime.stop(event.getServer());
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (ServerEconomyRuntime.isStarted(event.getServer()))
            ServerEconomyRuntime.get(event.getServer()).onServerTick();
    }
}
