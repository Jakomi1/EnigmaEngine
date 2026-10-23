package io.enigmaclient.mixin.xaero;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import io.enigmaclient.chunkmap.ChunkMapStore;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.highlight.DimensionHighlighterHandler;

/**
 * Watches the chunk map store from the world map's hash computation: whenever the drawn content of
 * a region changes, the region's cached hash is dropped so Xaero re-runs its own hash calculation
 * and re-renders the region.
 *
 * <p>Xaero only recomputes the hash of a region when its cache entry is missing, and the cache is
 * filled inside {@code getRegionHash}. The seen-content map therefore only decides <em>when</em> to
 * drop the cache entry; the actual hash still comes from Xaero's own calculation, including other
 * mods' highlighters.</p>
 *
 * <p>{@code getRegionHash} synchronizes on the handler instance, and this injection runs while that
 * monitor is held, so the seen-content map is guarded by the same monitor.</p>
 */
@Mixin(DimensionHighlighterHandler.class)
public abstract class WorldMapHighlighterHandlerMixin {

    @Shadow
    @Final
    private ResourceKey<Level> dimension;

    @Shadow
    public abstract void clearCachedHash(int regionX, int regionZ);

    @Unique
    private final Long2LongOpenHashMap enigmaclient$seenRegionContent = new Long2LongOpenHashMap();

    @Inject(method = "getRegionHash", at = @At("HEAD"))
    private void enigmaclient$refreshOnChunkMapChange(int regionX, int regionZ,
                                                      CallbackInfoReturnable<Integer> info) {
        long content = ChunkMapStore.INSTANCE.regionContent(
                this.dimension.identifier(), regionX, regionZ);
        long key = ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
        synchronized (this) {
            if (this.enigmaclient$seenRegionContent.get(key) == content) {
                return;
            }
            this.enigmaclient$seenRegionContent.put(key, content);
            this.clearCachedHash(regionX, regionZ);
        }
    }
}
