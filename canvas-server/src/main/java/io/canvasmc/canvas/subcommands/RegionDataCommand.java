package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.canvasmc.canvas.commands.Style;
import io.canvasmc.canvas.commands.SubCommand;
import io.canvasmc.canvas.region.RegionTickData;
import io.canvasmc.canvas.regionizer.RegionizerLagPriority;
import io.canvasmc.canvas.regionizer.RegionizerSettings;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

public class RegionDataCommand implements SubCommand {

    @Override
    public String getDescription() {
        return "Allows accessing and profiling region data";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(final LiteralArgumentBuilder<CommandSourceStack> base, final CommandBuildContext buildContext) {
        return base
            // Summarises the region the source is standing in: section count, players, entities,
            // chunks, tick cost and the split decision the regionizer would make for it.
            .then(net.minecraft.commands.Commands.literal("profile").executes(context -> {
                final CommandSourceStack source = context.getSource();
                final ServerLevel level = source.getLevel();
                final ServerPlayer player = source.getPlayer();

                final ChunkPos sourceChunk = sourceChunk(source, player);

                final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
                    level.regioniser.getRegionAtUnsynchronised(sourceChunk.x(), sourceChunk.z());
                if (region == null) {
                    Style.fail(source, "No region exists at the given coordinates");
                    return 0;
                }

                // every field below is owned by the region thread, so the read has to happen there
                final CompletableFuture<RegionProfile> profile = new CompletableFuture<>();
                RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(
                    level, sourceChunk.x(), sourceChunk.z(), () -> {
                        try {
                            final RegionizedWorldData worldData = level.getCurrentWorldData();

                            int sections = 0;
                            final it.unimi.dsi.fastutil.longs.LongIterator iterator = region.getOwnedSectionsUnsynchronised();
                            while (iterator.hasNext()) {
                                iterator.nextLong();
                                sections++;
                            }

                            int tileEntities = 0;
                            for (final LevelChunk tickingChunk : worldData.getTickingChunks()) {
                                tileEntities += tickingChunk.getBlockEntitiesCount();
                            }

                            final double mspt = region.getData().getMSPT(RegionTickData.Frame._5_SECONDS);

                            profile.complete(new RegionProfile(
                                region.id,
                                sections,
                                worldData.getPlayerCount(),
                                worldData.getEntityCount(),
                                worldData.getChunkCount(),
                                tileEntities,
                                mspt
                            ));
                        } catch (final Throwable thrown) {
                            profile.completeExceptionally(thrown);
                        }
                    },
                    ca.spottedleaf.concurrentutil.util.Priority.BLOCKING
                );

                try {
                    final RegionProfile result = profile.get(5, TimeUnit.SECONDS);
                    final RegionizerSettings settings = RegionizerSettings.get();
                    final RegionizerLagPriority.Decision decision = RegionizerLagPriority.from(settings).decide(
                        result.id(),
                        result.players(),
                        result.chunks(),
                        result.mspt(),
                        averageTickMillis()
                    );

                    Style.send(source, result.describe(decision));
                    return Command.SINGLE_SUCCESS;
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    Style.fail(source, "Interrupted while reading region data");
                    return 0;
                } catch (final Exception thrown) {
                    Style.fail(source, "Could not read region data: " + thrown.getMessage());
                    return 0;
                }
            }))
            // Lists every player currently inside a region, grouped by the region they are in.
            .then(net.minecraft.commands.Commands.literal("players").executes(context -> {
                final CommandSourceStack source = context.getSource();
                final Map<String, Integer> perRegion = new TreeMap<>();

                for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
                    level.regioniser.computeForAllRegionsUnsynchronised(region -> {
                        final ChunkPos center = region.getCenterChunk();
                        if (center == null) {
                            return;
                        }
                        perRegion.merge(regionKey(level, center.getMiddleBlockX(), center.getMiddleBlockZ()), 1, Integer::sum);
                    });
                }

                final Style.Report report = Style.report("Players by region");
                if (perRegion.isEmpty()) {
                    report.bullet("Active regions", Component.text("none", Style.SECONDARY));
                } else {
                    perRegion.forEach((key, count) ->
                        report.bullet(key, count + " player" + (count == 1 ? "" : "s"))
                    );
                }
                report.send(source);
                return Command.SINGLE_SUCCESS;
            }))
            // Entity breakdown of the region the source is in, with an optional type filter.
            .then(net.minecraft.commands.Commands.literal("entities")
                .then(net.minecraft.commands.Commands.argument("type", StringArgumentType.word())
                    .suggests((_, builder) -> {
                        for (final EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                            builder.suggest(EntityType.getKey(type).toString());
                        }
                        return builder.buildFuture();
                    })
                    .executes(context -> reportEntities(
                        context.getSource(),
                        StringArgumentType.getString(context, "type")
                    ))
                )
                .executes(context -> reportEntities(context.getSource(), null))
            )
            // Shows the split decision the regionizer would take for every active region. This is
            // the counterpart to the automatic pass in ThreadedRegionizer#enigma$runRegionMaintenance.
            .then(net.minecraft.commands.Commands.literal("scheduling").executes(context -> {
                final CommandSourceStack source = context.getSource();
                final RegionizerSettings settings = RegionizerSettings.get();
                final double globalMspt = averageTickMillis();
                final RegionizerLagPriority policy = RegionizerLagPriority.from(settings);

                final Style.Report report = Style.report("Regionizer scheduling");
                report.bullet("Split enabled", Style.onOff(settings.enabled()));
                report.bullet("Split high load MSPT", String.valueOf(settings.splitHighLoadMspt()));
                report.bullet("Split max sections", settings.splitMaxSections());
                report.bullet("Split min size", settings.splitMinSize());
                report.bullet("Global MSPT", Style.mspt(globalMspt));

                final List<RegionDecision> decisions = new ArrayList<>();
                for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
                    level.regioniser.computeForAllRegionsUnsynchronised(region -> {
                        final ChunkPos center = region.getCenterChunk();
                        if (center == null) {
                            return;
                        }
                        final double mspt = region.getData().getMSPT(RegionTickData.Frame._5_SECONDS);
                        decisions.add(new RegionDecision(
                            regionKey(level, center.getMiddleBlockX(), center.getMiddleBlockZ()),
                            mspt,
                            String.valueOf(policy.decide(region.id, 0, 0, mspt, globalMspt).priority())
                        ));
                    });
                }
                decisions.sort((a, b) -> a.key().compareTo(b.key()));

                if (decisions.isEmpty()) {
                    report.bullet("Regions", Component.text("none active", Style.SECONDARY));
                } else {
                    report.gap();
                    report.subHeader("Region decisions");
                    for (final RegionDecision decision : decisions) {
                        report.line(Component.text()
                            .append(Component.text(" - ", Style.LIST, TextDecoration.BOLD))
                            .append(Component.text(decision.key() + ": ", Style.PRIMARY))
                            .append(Style.mspt(decision.mspt()))
                            .append(Component.text(" MSPT, decision ", Style.SECONDARY))
                            .append(Style.value(decision.priority()))
                            .build());
                    }
                }
                report.send(source);
                return Command.SINGLE_SUCCESS;
            }));
    }

    @Override
    public String getName() {
        return "regiondata";
    }

    private static int reportEntities(final CommandSourceStack source, final String typeFilter) {
        final ServerLevel level = source.getLevel();
        final ServerPlayer player = source.getPlayer();
        final ChunkPos sourceChunk = sourceChunk(source, player);
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
            level.regioniser.getRegionAtUnsynchronised(sourceChunk.x(), sourceChunk.z());
        if (region == null) {
            Style.fail(source, "No region exists at the given coordinates");
            return 0;
        }

        final String filter = typeFilter == null ? null : typeFilter.toLowerCase(Locale.ROOT);
        final CompletableFuture<Map<String, Integer>> counts = new CompletableFuture<>();

        RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(
            level, sourceChunk.x(), sourceChunk.z(), () -> {
                final Map<String, Integer> result = new TreeMap<>();
                // only the entities the region itself tracks, so the result describes this
                // region and not the whole level
                final RegionizedWorldData worldData = level.getCurrentWorldData();
                for (final Entity entity : worldData.trackerEntities) {
                    final String name = EntityType.getKey(entity.getType()).toString();
                    if (filter != null && !name.toLowerCase(Locale.ROOT).contains(filter)) {
                        continue;
                    }
                    result.merge(name, 1, Integer::sum);
                }
                counts.complete(result);
            },
            ca.spottedleaf.concurrentutil.util.Priority.BLOCKING
        );

        try {
            final Map<String, Integer> result = counts.get(5, TimeUnit.SECONDS);
            final Style.Report report = Style.report(filter == null
                ? "Entities in this region"
                : "Entities matching '" + filter + "' in this region");
            if (result.isEmpty()) {
                report.bullet("Matches", Component.text("none", Style.SECONDARY));
            } else {
                result.forEach((name, count) -> report.bullet(name, count));
            }
            report.send(source);
            return Command.SINGLE_SUCCESS;
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            Style.fail(source, "Interrupted while reading entities");
            return 0;
        } catch (final Exception thrown) {
            Style.fail(source, "Could not read entities: " + thrown.getMessage());
            return 0;
        }
    }

    /**
     * Resolves the chunk the command source is standing in. A player's own position is used
     * when there is one, because the command source position of a player is not guaranteed to
     * be the position of the entity.
     */
    private static ChunkPos sourceChunk(final CommandSourceStack source, final ServerPlayer player) {
        if (player != null) {
            return new ChunkPos(player.getBlockX() >> 4, player.getBlockZ() >> 4);
        }
        return new ChunkPos(Mth.floor(source.getPosition().x) >> 4, Mth.floor(source.getPosition().z) >> 4);
    }

    private static double averageTickMillis() {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return 0.0D;
        }
        return (double) server.getAverageTickTimeNanos() / 1_000_000.0D;
    }

    private static String regionKey(final ServerLevel level, final int blockX, final int blockZ) {
        return level.getWorld().getName() + " region at " + blockX + ", " + blockZ;
    }

    private record RegionProfile(
        long id,
        int sections,
        int players,
        int entities,
        int chunks,
        int tileEntities,
        double mspt
    ) {

        Component describe(final RegionizerLagPriority.Decision decision) {
            return Style.report("Region " + this.id)
                .bullet("Sections", this.sections)
                .bullet("Players", this.players)
                .bullet("Entities", this.entities)
                .bullet("Chunks", this.chunks)
                .bullet("Tile entities", this.tileEntities)
                .bullet("MSPT 5s", Style.mspt(this.mspt))
                .bullet("Load ratio", Style.value(decision.loadRatio()))
                .bullet("Density", Style.value(decision.density()))
                .bullet("Split factor", String.valueOf(decision.splitFactor()))
                .bullet("Priority", String.valueOf(decision.priority()))
                .bullet("Isolate", String.valueOf(decision.isolate()))
                .build();
        }
    }

    private record RegionDecision(String key, double mspt, String priority) {
    }
}
