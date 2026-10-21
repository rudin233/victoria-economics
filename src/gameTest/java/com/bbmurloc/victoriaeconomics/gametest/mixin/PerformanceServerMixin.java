package com.bbmurloc.victoriaeconomics.gametest.mixin;
import com.bbmurloc.victoriaeconomics.gametest.PerformanceMeasurements;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.BooleanSupplier;
/** Test mod only: includes the Post callbacks omitted by Minecraft's own tickTimesNanos. */
@Mixin(MinecraftServer.class)
public abstract class PerformanceServerMixin {
    @Inject(method="tickServer",at=@At("HEAD"))
    private void veBeforeTick(BooleanSupplier budget,CallbackInfo ci){PerformanceMeasurements.beforeTick((MinecraftServer)(Object)this);}
    @Inject(method="tickServer",at=@At("RETURN"))
    private void veAfterTick(BooleanSupplier budget,CallbackInfo ci){PerformanceMeasurements.afterTick((MinecraftServer)(Object)this);}
}
