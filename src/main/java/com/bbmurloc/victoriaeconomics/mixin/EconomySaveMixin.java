package com.bbmurloc.victoriaeconomics.mixin;

import com.bbmurloc.victoriaeconomics.server.ServerEconomyRuntime;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** One server-wide request, not one per LevelEvent.Save. flush waits for the actual VE commit. */
@Mixin(MinecraftServer.class)
abstract class EconomySaveMixin {
    @Inject(method = "saveAllChunks", at = @At("HEAD"))
    private void ve$capture(boolean suppressLog, boolean flush, boolean forced, CallbackInfoReturnable<Boolean> ci) {
        var server = (MinecraftServer) (Object) this;
        if (!forced && java.util.stream.StreamSupport.stream(server.getAllLevels().spliterator(), false).allMatch(level -> level.noSave)) return;
        if (ServerEconomyRuntime.isStarted(server)) try {
            ServerEconomyRuntime.get(server).onNativeSave(flush, forced);
        } catch (RuntimeException failure) {
            com.bbmurloc.victoriaeconomics.VictoriaEconomics.LOGGER.error("VE snapshot capture FAILED; native saving continues, VE save is not confirmed", failure);
        }
    }
    @Inject(method = "saveAllChunks", at = @At("RETURN"))
    private void ve$awaitFlush(boolean suppressLog, boolean flush, boolean forced, CallbackInfoReturnable<Boolean> ci) {
        var server = (MinecraftServer) (Object) this;
        if (flush && ServerEconomyRuntime.isStarted(server)) ServerEconomyRuntime.get(server).awaitNativeSave();
    }
}
