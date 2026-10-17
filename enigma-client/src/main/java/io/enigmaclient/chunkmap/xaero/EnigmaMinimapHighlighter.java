package io.enigmaclient.chunkmap.xaero;

import io.enigmaclient.EnigmaClient;
import io.enigmaclient.config.EnigmaConfig;
import io.enigmaclient.chunkmap.ChunkMapStore;
import io.enigmaclient.chunkmap.HighlightPattern;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.common.minimap.highlight.ChunkHighlighter;
import xaero.hud.minimap.info.render.compile.InfoDisplayCompiler;

/**
 * Draws the server reported chunk map onto Xaero's Minimap. Loaded chunks get a solid tint, ticking
 * chunks a dashed tint on top.
 */
public final class EnigmaMinimapHighlighter extends ChunkHighlighter {

    public static final EnigmaMinimapHighlighter INSTANCE = new EnigmaMinimapHighlighter();

    private EnigmaMinimapHighlighter() {
        super(true);
    }

    private static EnigmaConfig config() {
        EnigmaConfig config = EnigmaClient.config;
        return config != null && config.showChunkMap ? config : null;
    }

    @Override
    public boolean regionHasHighlights(ResourceKey<Level> level, int regionX, int regionZ) {
        return config() != null
                && ChunkMapStore.INSTANCE.regionHasHighlights(level.identifier(), regionX, regionZ);
    }

    @Override
    public boolean chunkIsHighlit(ResourceKey<Level> level, int chunkX, int chunkZ) {
        return config() != null
                && ChunkMapStore.INSTANCE.state(level.identifier(), chunkX, chunkZ) != ChunkMapStore.NONE;
    }

    @Override
    protected int[] getColors(ResourceKey<Level> level, int chunkX, int chunkZ) {
        return null;
    }

    @Override
    public int[] getChunkHighlitColor(ResourceKey<Level> level, int chunkX, int chunkZ) {
        EnigmaConfig config = config();
        if (config == null) {
            return null;
        }
        byte state = ChunkMapStore.INSTANCE.state(level.identifier(), chunkX, chunkZ);
        if (state == ChunkMapStore.TICKING) {
            return HighlightPattern.dashed(this.resultStore, config.chunkMapTickingColor);
        }
        if (state == ChunkMapStore.LOADED) {
            return HighlightPattern.solid(this.resultStore, config.chunkMapLoadedColor);
        }
        return null;
    }

    @Override
    public void addChunkHighlightTooltips(InfoDisplayCompiler compiler, ResourceKey<Level> level,
                                          int chunkX, int chunkZ, int color) {
        byte state = ChunkMapStore.INSTANCE.state(level.identifier(), chunkX, chunkZ);
        if (state == ChunkMapStore.TICKING) {
            compiler.addLine(EnigmaClient.prefix("Chunk " + chunkX + ", " + chunkZ + " - getickt"));
        } else if (state == ChunkMapStore.LOADED) {
            compiler.addLine(EnigmaClient.prefix("Chunk " + chunkX + ", " + chunkZ + " - geladen"));
        }
    }
}
