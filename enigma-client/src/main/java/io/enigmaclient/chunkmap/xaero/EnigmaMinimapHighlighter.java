package io.enigmaclient.chunkmap.xaero;

import io.enigmaclient.EnigmaClient;
import io.enigmaclient.config.EnigmaConfig;
import io.enigmaclient.chunkmap.ChunkMapStore;
import io.enigmaclient.chunkmap.HighlightPattern;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.common.minimap.highlight.ChunkHighlighter;
import xaero.hud.minimap.info.render.compile.InfoDisplayCompiler;

/**
 * Draws the server reported chunk map onto Xaero's Minimap: every chunk the server has loaded is
 * filled green. Hovering a chunk shows the region that owns it.
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

    private static String stateName(final byte state) {
        return state == ChunkMapStore.TICKING ? "Ticking" : state == ChunkMapStore.BUFFER ? "Fusions-Buffer" : "?";
    }

    private static String statValue(final int value) {
        return value < 0 ? "?" : Integer.toString(value);
    }

    @Override
    public boolean regionHasHighlights(ResourceKey<Level> level, int regionX, int regionZ) {
        if (config() == null) {
            return false;
        }
        return ChunkMapStore.INSTANCE.regionHasHighlights(level.identifier(), regionX, regionZ);
    }

    @Override
    public boolean chunkIsHighlit(ResourceKey<Level> level, int chunkX, int chunkZ) {
        if (config() == null) {
            return false;
        }
        final byte state = ChunkMapStore.INSTANCE.state(level.identifier(), chunkX, chunkZ);
        return state == ChunkMapStore.TICKING || (state == ChunkMapStore.BUFFER && config().showChunkMapBuffer);
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
        final byte state = ChunkMapStore.INSTANCE.state(level.identifier(), chunkX, chunkZ);
        if (state == ChunkMapStore.TICKING) {
            return HighlightPattern.solid(this.resultStore, config.chunkMapTickingColor);
        }
        if (state == ChunkMapStore.BUFFER && config.showChunkMapBuffer) {
            return HighlightPattern.solid(this.resultStore, config.chunkMapBufferColor);
        }
        return null;
    }

    @Override
    public void addChunkHighlightTooltips(InfoDisplayCompiler compiler, ResourceKey<Level> level,
                                          int chunkX, int chunkZ, int blockY) {
        EnigmaConfig config = config();
        if (config == null) {
            return;
        }
        ChunkMapStore.ChunkInfo info = ChunkMapStore.INSTANCE.chunkInfo(level.identifier(), chunkX, chunkZ);
        if (info.state() == ChunkMapStore.BUFFER && !config.showChunkMapBuffer) {
            return;
        }
        if (info.state() == ChunkMapStore.NONE && !info.hasRegion()) {
            return;
        }
        compiler.addLine(Component.literal("Chunk " + chunkX + ", " + chunkZ
                + (info.state() == ChunkMapStore.NONE ? "" : " - " + stateName(info.state()))));
        if (info.state() == ChunkMapStore.BUFFER) {
            compiler.addLine(Component.literal("Buffer-Radius: " + ChunkMapStore.INSTANCE.bufferRadiusChunks() + " Chunks"));
        }
        if (info.hasRegion()) {
            compiler.addLine(Component.literal("Region #" + info.region()));
            if (config.chunkMapRegionDebug && info.stats() != null) {
                ChunkMapStore.RegionStats stats = info.stats();
                compiler.addLine(Component.literal("Entities: " + statValue(stats.entities())
                        + ", Chunks: " + stats.chunks()));
                compiler.addLine(Component.literal(String.format("MSPT: %.2f, TPS: %.2f",
                        stats.msptMilli() / 1000.0D, stats.tpsMilli() / 1000.0D)));
            }
        } else {
            compiler.addLine(Component.literal("keine Region"));
        }
    }
}
