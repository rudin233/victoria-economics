package com.bbmurloc.victoriaeconomics.gametest;

import net.neoforged.fml.common.Mod;

/** Loaded only by the isolated GameTest run, never by the shipping mod. */
@Mod("victoria_economics_gametests")
public final class AcceptanceTestMod {
    public AcceptanceTestMod() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,(net.neoforged.neoforge.event.server.ServerAboutToStartEvent event)->RestartAcceptanceGameTests.beforeContext(event.getServer()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,(net.neoforged.neoforge.event.server.ServerStartingEvent event)->RestartAcceptanceGameTests.afterContext(event.getServer()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Pre event)->AcceptanceMetrics.beforeTick());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post event)->{AcceptanceMetrics.afterTick();RestartAcceptanceGameTests.afterLogicalTick();});
    }
}
