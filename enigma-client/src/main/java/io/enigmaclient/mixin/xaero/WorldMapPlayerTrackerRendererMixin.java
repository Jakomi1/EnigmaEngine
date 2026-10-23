package io.enigmaclient.mixin.xaero;

import io.enigmaclient.chunkmap.player.EnigmaPlayerMarker;
import io.enigmaclient.chunkmap.xaero.EnigmaPlayerTrackerSupport;
import io.enigmaclient.chunkmap.xaero.WorldMapMarkerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.element.MapElementGraphics;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.map.radar.tracker.PlayerTrackerMapElement;
import xaero.map.radar.tracker.PlayerTrackerMapElementRenderer;

/**
 * Replaces Xaero's skin head icon with the generic Enigma player marker for elements produced by
 * the Enigma tracker. The handler has already translated the pose to the element's screen
 * position, so the marker is drawn at the pose origin.
 */
@Mixin(PlayerTrackerMapElementRenderer.class)
public abstract class WorldMapPlayerTrackerRendererMixin {

    @Inject(
            method = "renderElement(Lxaero/map/radar/tracker/PlayerTrackerMapElement;ZDFDDLxaero/map/element/render/ElementRenderInfo;Lxaero/map/element/MapElementGraphics;Lxaero/lib/client/graphics/XaeroBufferProvider;Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void enigma$renderGenericMarker(final PlayerTrackerMapElement<?> element,
                                            final boolean renderedOnRadar,
                                            final double depth,
                                            final float partialTick,
                                            final double subX,
                                            final double subZ,
                                            final ElementRenderInfo info,
                                            final MapElementGraphics graphics,
                                            @Coerce final Object bufferProvider,
                                            final MultiTextureRenderTypeRendererProvider rendererProvider,
                                            final CallbackInfoReturnable<Boolean> cir) {
        if (element.getPlayer() instanceof final EnigmaPlayerMarker marker) {
            if (info.location == ElementRenderLocation.IN_WORLD
                    && EnigmaPlayerTrackerSupport.mapOnly()) {
                // "Nur auf Karte": keep the marker off the actual game world (IN_WORLD) and only on
                // the maps.
                cir.setReturnValue(false);
                return;
            }
            cir.setReturnValue(WorldMapMarkerRenderer.draw(graphics, marker));
        }
    }
}
