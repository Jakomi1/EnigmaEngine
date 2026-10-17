package io.enigmaclient.mixin.xaero;

import io.enigmaclient.chunkmap.ChunkMapStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.common.minimap.highlight.DimensionHighlighterHandler;

/**
 * Folds the Enigma chunk overlay revision into the minimap highlight version so the loaded region
 * is re-rendered whenever the server sends a new chunk snapshot.
 */
@Mixin(DimensionHighlighterHandler.class)
public abstract class MinimapDimensionHighlighterHandlerMixin {

    @Inject(method = "getVersion", at = @At("RETURN"), cancellable = true)
    private void enigmaclient$includeChunkMapRevision(CallbackInfoReturnable<Integer> info) {
        info.setReturnValue(info.getReturnValueI() + (int) (ChunkMapStore.INSTANCE.revision() & 0xFFFFL));
    }
}
