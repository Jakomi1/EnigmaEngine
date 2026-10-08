package io.canvasmc.canvas.distributed.command;

import io.canvasmc.canvas.distributed.DistributedBootstrap;
import io.canvasmc.canvas.distributed.runtime.RemoteRegionExecutor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Display numbering for regions. The real region ids are process-local {@link java.util.concurrent.atomic.AtomicLong}
 * values that jump (0, 2, 6, 14, ...) and mean nothing to an operator, so commands number the
 * regions 1..N instead. The numbering is purely cosmetic: every lookup falls back to the real id
 * and no wire format, lease or migration ever sees these numbers.
 *
 * <p>Entries are sorted by (world, region id) so consecutive calls agree as long as the region
 * set does not change between them.</p>
 *
 * <p>Regions that were handed over to a Compute Host are gone from the local regionizer but
 * stay addressable: they join the listing with {@link Entry#forwardedToHost()} set, so
 * {@code info}, {@code migrate} and the region lists keep referring to them by the same
 * {@code #N} number.</p>
 */
public final class RegionNumbering {

    /**
     * @param number one based display number as printed by the commands
     * @param regionId the real, process local region id
     * @param worldPath dimension path of the region's level
     * @param chunks owned chunk count (for forwarded regions: chunks carried away)
     * @param sections owned section count (0 for forwarded regions, they are not local anymore)
     * @param forwardedToHost instance id of the Compute Host this region was forwarded to,
     *                        or null while the region is still ticked locally
     */
    public record Entry(
            int number,
            long regionId,
            String worldPath,
            int chunks,
            int sections,
            @Nullable Long forwardedToHost
    ) {
        public boolean forwarded() {
            return this.forwardedToHost != null;
        }
    }

    private record Forwarded(String worldPath, long targetInstanceId, int chunks) {}

    private RegionNumbering() {
    }

    /**
     * Lists every region of every loaded level plus every region handed over to a Compute
     * Host, sorted by (world, region id) and numbered from 1.
     */
    public static List<Entry> list(final MinecraftServer server) {
        final Map<Long, Forwarded> forwarded = loadForwarded();
        final Map<Long, Forwarded> gone = new HashMap<>(forwarded);

        final List<Candidate> raw = new ArrayList<>();
        for (final ServerLevel level : server.getAllLevels()) {
            final String worldPath = level.dimension().identifier().getPath();
            level.regioniser.computeForAllRegions(region -> {
                final Forwarded forwardedEntry = forwarded.get(region.id);
                if (forwardedEntry != null) {
                    // still releasing locally: the forwarding record is the newer truth
                    gone.remove(region.id);
                    raw.add(new Candidate(worldPath, region.id,
                            forwardedEntry.chunks(), 0, forwardedEntry.targetInstanceId()));
                    return;
                }
                raw.add(new Candidate(worldPath, region.id,
                        region.getOwnedPackedChunkPositions().length,
                        region.getOwnedSections().size(),
                        null));
                gone.remove(region.id);
            });
        }
        for (final Map.Entry<Long, Forwarded> entry : gone.entrySet()) {
            final Forwarded forwardedRegion = entry.getValue();
            raw.add(new Candidate(forwardedRegion.worldPath(), entry.getKey(),
                    forwardedRegion.chunks(), 0, forwardedRegion.targetInstanceId()));
        }

        raw.sort(Comparator.comparing((final Candidate entry) -> entry.worldPath)
                .thenComparingLong(Candidate::regionId));

        final List<Entry> entries = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            final Candidate entry = raw.get(i);
            entries.add(new Entry(i + 1, entry.regionId(), entry.worldPath(),
                    entry.chunks(), entry.sections(), entry.forwardedToHost()));
        }
        return entries;
    }

    private static Map<Long, Forwarded> loadForwarded() {
        try {
            final RemoteRegionExecutor executor = DistributedBootstrap.getInstance().getRegionExecutor();
            if (executor == null) {
                return Map.of();
            }
            final Map<Long, Forwarded> forwarded = new HashMap<>();
            for (final RemoteRegionExecutor.ForwardedRegion entry : executor.forwardedRegions()) {
                forwarded.put(entry.regionId(), new Forwarded(entry.worldPath(), entry.targetInstanceId(), entry.chunkCount()));
            }
            return forwarded;
        } catch (final Throwable ignored) {
            // distributed runtime not available - the local listing is still valid
            return Map.of();
        }
    }

    /**
     * Resolves a command argument to a region id. Display numbers printed by the commands win;
     * when no display number matches, the raw value is tried as an internal region id so ids
     * copied from logs keep working.
     *
     * @return the internal region id, or empty when the argument matches nothing
     */
    public static OptionalLong resolve(final MinecraftServer server, final long argument) {
        final List<Entry> entries = list(server);
        if (argument >= 1L && argument <= entries.size()) {
            return OptionalLong.of(entries.get((int) argument - 1).regionId());
        }
        for (final Entry entry : entries) {
            if (entry.regionId() == argument) {
                return OptionalLong.of(argument);
            }
        }
        return OptionalLong.empty();
    }

    /**
     * Renders a region as {@code #3 (id=6)}, or {@code id=6} when it is not part of the listing.
     */
    public static String label(final List<Entry> entries, final long regionId) {
        for (final Entry entry : entries) {
            if (entry.regionId() == regionId) {
                return "#" + entry.number() + " (id=" + regionId + ")";
            }
        }
        return "id=" + regionId;
    }

    private record Candidate(
            String worldPath,
            long regionId,
            int chunks,
            int sections,
            @Nullable Long forwardedToHost
    ) {}
}
