package com.bbmurloc.victoriaeconomics.server.event;

import com.bbmurloc.victoriaeconomics.server.ServerEconomyRuntime;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

public final class ServerLifecycleEvents {

    private ServerLifecycleEvents() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ServerLifecycleEvents::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(ServerLifecycleEvents::onServerStopping);
    }

    private static void onServerAboutToStart(ServerAboutToStartEvent event) {
        ServerEconomyRuntime.start(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        ServerEconomyRuntime.stop(event.getServer());
    }
}
