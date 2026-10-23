package io.enigmaclient.chunkmap.xaero;

import io.enigmaclient.EnigmaClient;
import io.enigmaclient.config.EnigmaConfig;
import io.enigmaclient.chunkmap.ChunkMapStore;
import io.enigmaclient.chunkmap.HighlightPattern;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.highlight.ChunkHighlighter;

/**
 * Draws the server reported chunk map onto Xaero's World Map: every chunk the server has loaded is
 * filled green. Hovering a chunk shows the region that owns it.
 */
public final class EnigmaWorldMapHighlighter extends ChunkHighlighter {

    public static final EnigmaWorldMapHighlighter INSTANCE = new EnigmaWorldMapHighlighter();

    private EnigmaWorldMapHighlighter() {
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
    public int calculateRegionHash(ResourceKey<Level> level, int regionX, int regionZ) {
        if (config() == null) {
            return 0;
        }
        long hash = ChunkMapStore.INSTANCE.regionContent(level.identifier(), regionX, regionZ);
        return (int) (hash ^ (hash >>> 32));
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
    public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> level, int chunkX, int chunkZ) {
        return chunkTooltip(level, chunkX, chunkZ);
    }

    @Override
    public Component getChunkHighlightBluntTooltip(ResourceKey<Level> level, int chunkX, int chunkZ) {
        return chunkTooltip(level, chunkX, chunkZ);
    }

    /**
     * Single line chunk tooltip. Xaero's world map renders the tooltip as one line, so no line break
     * characters are used; the fields are appended with {@code " - "} separators instead.
     */
    private static Component chunkTooltip(ResourceKey<Level> level, int chunkX, int chunkZ) {
        EnigmaConfig config = config();
        if (config == null) {
            return null;
        }
        ChunkMapStore.ChunkInfo info = ChunkMapStore.INSTANCE.chunkInfo(level.identifier(), chunkX, chunkZ);
        if (info.state() == ChunkMapStore.BUFFER && !config.showChunkMapBuffer) {
            return null;
        }
        if (info.state() == ChunkMapStore.NONE && !info.hasRegion()) {
            return null;
        }
        StringBuilder text = new StringBuilder("Chunk ").append(chunkX).append(", ").append(chunkZ);
        if (info.state() != ChunkMapStore.NONE) {
            text.append(" - ").append(stateName(info.state()));
        }
        if (info.state() == ChunkMapStore.BUFFER) {
            text.append(" (Radius: ").append(ChunkMapStore.INSTANCE.bufferRadiusChunks()).append(" Chunks)");
        }
        if (info.hasRegion()) {
            text.append(" - Region #").append(info.region());
            if (config.chunkMapRegionDebug && info.stats() != null) {
                ChunkMapStore.RegionStats stats = info.stats();
                text.append(" - Entities: ").append(statValue(stats.entities()))
                        .append(", Chunks: ").append(stats.chunks())
                        .append(String.format(", MSPT: %.2f", stats.msptMilli() / 1000.0D))
                        .append(String.format(", TPS: %.2f", stats.tpsMilli() / 1000.0D));
            }
        } else {
            text.append(" - keine Region");
        }
        return Component.literal(text.toString());
    }

    @Override
    public void addMinimapBlockHighlightTooltips(java.util.List<Component> tooltips, ResourceKey<Level> level,
                                                 int blockX, int blockY, int blockZ) {
        // Block level tooltips are not used for the chunk map; chunk tooltips cover the map.
    }
}
