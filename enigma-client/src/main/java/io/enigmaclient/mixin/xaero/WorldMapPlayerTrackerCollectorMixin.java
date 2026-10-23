package io.enigmaclient.mixin.xaero;

import io.enigmaclient.chunkmap.xaero.EnigmaWorldMapPlayerTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.radar.tracker.PlayerTrackerMapElementCollector;
import xaero.map.radar.tracker.system.PlayerTrackerSystemManager;

/**
 * Registers the Enigma player tracker (server reported player positions) into Xaero's World Map
 * tracker manager once, so the chunk map's player markers are collected and rendered like any
 * other tracked player.
 */
@Mixin(PlayerTrackerMapElementCollector.class)
public abstract class WorldMapPlayerTrackerCollectorMixin {

    @Shadow
    @Final
    private PlayerTrackerSystemManager systemManager;

    @Inject(method = "update(Lnet/minecraft/client/Minecraft;)V", at = @At("HEAD"))
    private void enigma$registerTracker(final Minecraft minecraft, final CallbackInfo ci) {
        EnigmaWorldMapPlayerTracker.ensureRegistered(this.systemManager);
    }
}
