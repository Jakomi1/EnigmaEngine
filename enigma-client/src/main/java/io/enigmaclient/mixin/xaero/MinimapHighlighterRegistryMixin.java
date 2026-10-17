package io.enigmaclient.mixin.xaero;

import io.enigmaclient.chunkmap.xaero.EnigmaMinimapHighlighter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.common.minimap.highlight.HighlighterRegistry;

/**
 * Registers the Enigma chunk overlay with Xaero's Minimap right before the registry is sealed.
 */
@Mixin(HighlighterRegistry.class)
public abstract class MinimapHighlighterRegistryMixin {

    @Inject(method = "end", at = @At("HEAD"))
    private void enigmaclient$registerHighlighter(CallbackInfo info) {
        ((HighlighterRegistry) (Object) this).register(EnigmaMinimapHighlighter.INSTANCE);
    }
}
