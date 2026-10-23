package io.enigmaclient.mixin.xaero;

import io.enigmaclient.chunkmap.xaero.EnigmaMinimapPlayerTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.hud.minimap.player.tracker.PlayerTrackerMinimapElementCollector;
import xaero.hud.minimap.player.tracker.system.RenderedPlayerTrackerManager;

/**
 * Registers the Enigma player tracker (server reported player positions) into Xaero's Minimap
 * tracker manager once, so the chunk map's player markers are collected and rendered like any
 * other tracked player.
 */
@Mixin(PlayerTrackerMinimapElementCollector.class)
public abstract class MinimapPlayerTrackerCollectorMixin {

    @Shadow
    @Final
    private RenderedPlayerTrackerManager systemManager;

    @Inject(method = "update(Lnet/minecraft/client/Minecraft;)V", at = @At("HEAD"))
    private void enigma$registerTracker(final Minecraft minecraft, final CallbackInfo ci) {
        EnigmaMinimapPlayerTracker.ensureRegistered(this.systemManager);
    }
}
