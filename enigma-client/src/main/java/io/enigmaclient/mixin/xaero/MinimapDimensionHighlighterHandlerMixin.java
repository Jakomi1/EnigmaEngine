package io.enigmaclient.mixin.xaero;

import io.enigmaclient.chunkmap.ChunkMapStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.common.minimap.highlight.DimensionHighlighterHandler;

/**
 * Folds the Enigma chunk overlay revision into the minimap highlight version so the rendered
 * highlights are rebuilt whenever the server sends a new snapshot or the view box moves.
 */
@Mixin(DimensionHighlighterHandler.class)
public abstract class MinimapDimensionHighlighterHandlerMixin {

    @Inject(method = "getVersion", at = @At("RETURN"), cancellable = true)
    private void enigmaclient$includeChunkMapRevision(CallbackInfoReturnable<Integer> info) {
        long revision = ChunkMapStore.INSTANCE.revision();
        info.setReturnValue(info.getReturnValueI() + (int) (revision ^ (revision >>> 32)));
    }
}
