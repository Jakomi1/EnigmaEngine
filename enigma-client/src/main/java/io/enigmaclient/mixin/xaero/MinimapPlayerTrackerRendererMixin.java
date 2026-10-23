package io.enigmaclient.mixin.xaero;

import io.enigmaclient.chunkmap.player.EnigmaPlayerMarker;
import io.enigmaclient.chunkmap.xaero.EnigmaPlayerTrackerSupport;
import io.enigmaclient.chunkmap.xaero.MinimapMarkerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.hud.minimap.element.render.MinimapElementGraphics;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.player.tracker.PlayerTrackerMinimapElement;
import xaero.hud.minimap.player.tracker.PlayerTrackerMinimapElementRenderer;

/**
 * Replaces Xaero's skin head icon with the generic Enigma player marker for elements produced by
 * the Enigma tracker. The handler has already translated the pose to the element's screen
 * position, so the marker is drawn at the pose origin.
 */
@Mixin(PlayerTrackerMinimapElementRenderer.class)
public abstract class MinimapPlayerTrackerRendererMixin {

    @Inject(
            method = "renderElement(Lxaero/hud/minimap/player/tracker/PlayerTrackerMinimapElement;ZZDFDDLxaero/hud/minimap/element/render/MinimapElementRenderInfo;Lxaero/hud/minimap/element/render/MinimapElementGraphics;Lxaero/lib/client/graphics/XaeroBufferProvider;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void enigma$renderGenericMarker(final PlayerTrackerMinimapElement<?> element,
                                            final boolean renderedOnRadar,
                                            final boolean offscreen,
                                            final double depth,
                                            final float partialTick,
                                            final double subX,
                                            final double subZ,
                                            final MinimapElementRenderInfo info,
                                            final MinimapElementGraphics graphics,
                                            @Coerce final Object bufferProvider,
                                            final CallbackInfoReturnable<Boolean> cir) {
        if (element.getPlayer() instanceof final EnigmaPlayerMarker marker) {
            if (info.location == MinimapElementRenderLocation.IN_WORLD
                    && EnigmaPlayerTrackerSupport.mapOnly()) {
                // "Nur auf Karte": Xaero renders tracked players in the actual game world too
                // (IN_WORLD). Suppress the generic marker there so it only shows on the maps.
                cir.setReturnValue(false);
                return;
            }
            cir.setReturnValue(MinimapMarkerRenderer.draw(graphics, marker));
        }
    }
}
