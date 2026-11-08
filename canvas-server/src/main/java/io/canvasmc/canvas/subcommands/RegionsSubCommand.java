package io.canvasmc.canvas.subcommands;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.canvasmc.canvas.commands.Style;
import io.canvasmc.canvas.commands.SubCommand;
import io.canvasmc.canvas.distributed.command.RegionNumbering;
import io.canvasmc.canvas.region.RegionTickData;
import io.canvasmc.canvas.region.WorldRegionizer;
import io.canvasmc.canvas.regionizer.RegionizerLagPriority;
import io.canvasmc.canvas.regionizer.RegionizerSettings;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.ThreadedRegionizer.ThreadedRegion;
import io.papermc.paper.threadedregions.TickRegions;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Live view and emergency operations for regions. Region selectors are shared across inspection
 * and force-unload commands so suggestions and lookup behavior stay consistent.
 *
 * <p>A region is selected either as {@code here} (the region at the sender's position), as
 * {@code #N} using the display number from {@code /enigma regions list}, or as the raw internal
 * region id copied from a log. Display numbers and raw ids are deliberately disjoint syntax so
 * there is never a question which one was meant.</p>
 *
 * <p>Section counts always come from the regionizer's own section table and are reported
 * separately from owned chunk counts.</p>
 */
public final class RegionsSubCommand implements SubCommand {

    private static final SimpleCommandExceptionType INVALID_REGION = new SimpleCommandExceptionType(
        net.minecraft.network.chat.Component.literal("Invalid region. Use #N from /enigma regions list, the raw region id, or \"here\".")
    );
    private static final SimpleCommandExceptionType UNKNOWN_REGION = new SimpleCommandExceptionType(
        net.minecraft.network.chat.Component.literal("No live region matches that number or id.")
    );
    private static final SimpleCommandExceptionType NO_REGION_AT_SOURCE = new SimpleCommandExceptionType(
        net.minecraft.network.chat.Component.literal("No region exists at your position.")
    );
    private static final SimpleCommandExceptionType FORWARDED_REGION = new SimpleCommandExceptionType(
        net.minecraft.network.chat.Component.literal("That region is forwarded to a Compute Host and is no longer ticked on this process.")
    );
    private static final SimpleCommandExceptionType EMPTY_REGION = new SimpleCommandExceptionType(
        net.minecraft.network.chat.Component.literal("That region has no chunks and cannot be profiled.")
    );

    private static final int MAX_SECTION_LINES = 100;

    @Override
    public String getDescription() {
        return "Lists live regions with their real sections and profiles individual regions";
    }

    @Override
    public String getName() {
        return "regions";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(final LiteralArgumentBuilder<CommandSourceStack> base, final CommandBuildContext buildContext) {
        return base
            .then(literal("list")
                .requires(this.check("list"))
                .executes(context -> list(context, null))
                .then(argument("dimension", DimensionArgument.dimension())
                    .executes(context -> list(context, DimensionArgument.getDimension(context, "dimension")))))
            .then(literal("show")
                .requires(this.check("show"))
                .executes(context -> show(context, null))
                .then(argument("region", StringArgumentType.string())
                    .suggests(RegionsSubCommand::suggestRegions)
                    .executes(context -> show(context, StringArgumentType.getString(context, "region")))))
            .then(literal("sections")
                .requires(this.check("sections"))
                .executes(context -> sections(context, null))
                .then(argument("region", StringArgumentType.string())
                    .suggests(RegionsSubCommand::suggestRegions)
                    .executes(context -> sections(context, StringArgumentType.getString(context, "region")))))
            .then(literal("at")
                .requires(this.check("at"))
                .then(argument("x", IntegerArgumentType.integer())
                    .then(argument("z", IntegerArgumentType.integer())
                        .executes(context -> at(context, null))
                        .then(argument("dimension", DimensionArgument.dimension())
                            .executes(context -> at(context, DimensionArgument.getDimension(context, "dimension")))))))
            .then(literal("players")
                .requires(this.check("players"))
                .executes(RegionsSubCommand::players))
            .then(literal("entities")
                .requires(this.check("entities"))
                .executes(context -> entities(context, null, null))
                .then(argument("region", StringArgumentType.string())
                    .suggests(RegionsSubCommand::suggestRegions)
                    .executes(context -> entities(context, StringArgumentType.getString(context, "region"), null))
                    .then(argument("type", StringArgumentType.string())
                        .suggests(RegionsSubCommand::suggestEntityTypes)
                        .executes(context -> entities(
                            context,
                            StringArgumentType.getString(context, "region"),
                            StringArgumentType.getString(context, "type")
                        )))))
            .then(literal("decisions")
                .requires(this.check("decisions"))
                .executes(RegionsSubCommand::decisions))
            .then(literal("force-unload")
                .requires(this.check("force-unload"))
                .then(argument("region", StringArgumentType.word())
                    .suggests(RegionsSubCommand::suggestRegions)
                    .executes(RegionsSubCommand::forceUnload)));
    }


    private static int list(final CommandContext<CommandSourceStack> context, final @Nullable ServerLevel only) {
        final CommandSourceStack source = context.getSource();
        final MinecraftServer server = source.getServer();
        final Map<Long, LiveSnapshot> live = snapshot(server);

        final String onlyPath = only == null ? null : only.dimension().identifier().getPath();
        final List<RegionNumbering.Entry> entries = new ArrayList<>();
        for (final RegionNumbering.Entry entry : RegionNumbering.list(server)) {
            if (onlyPath == null || entry.worldPath().equals(onlyPath)) {
                entries.add(entry);
            }
        }

        final Style.Report report = Style.report(onlyPath == null ? "Regions" : "Regions in " + onlyPath);
        if (entries.isEmpty()) {
            report.bullet("Regions", Component.text("none", Style.SECONDARY));
            report.send(source);
            return Command.SINGLE_SUCCESS;
        }

        long ticking = 0;
        long idle = 0;
        long sections = 0;
        long chunks = 0;
        for (final RegionNumbering.Entry entry : entries) {
            sections += entry.sections();
            chunks += entry.chunks();
            final LiveSnapshot snapshot = live.get(entry.regionId());
            if (snapshot != null && snapshot.state() == WorldRegionizer.ChunkRegion.State.TICKING) {
                ticking++;
            } else {
                idle++;
            }
        }

        report.bullet("Total", Component.text()
            .append(Style.value(entries.size()))
            .append(Component.text(" regions (ticking=", Style.SECONDARY))
            .append(Style.value(ticking))
            .append(Component.text(", idle=", Style.SECONDARY))
            .append(Style.value(idle))
            .append(Component.text(")", Style.SECONDARY))
            .build());
        report.bullet("Sections", Style.value(sections));
        report.bullet("Chunks", Style.value(chunks));
        report.gap();

        for (final RegionNumbering.Entry entry : entries) {
            report.line(renderRow(entry, live.get(entry.regionId())));
        }

        report.line(Style.value("Select one with /enigma regions show <#N|id|here>"));
        report.send(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int show(final CommandContext<CommandSourceStack> context, final @Nullable String selector) throws CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        final Located located = locate(source, selector);

        if (located.forwarded()) {
            final RegionNumbering.Entry entry = Objects.requireNonNull(located.entry());
            Style.report("Region " + located.label())
                .bullet("World", entry.worldPath())
                .bullet("Chunks", entry.chunks())
                .bullet("Forwarded to", "Compute Host " + entry.forwardedToHost())
                .bullet("Note", "the region is no longer ticked on this process")
                .send(source);
            return Command.SINGLE_SUCCESS;
        }

        final ServerLevel level = Objects.requireNonNull(located.level());
        final ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = Objects.requireNonNull(located.region());
        final ChunkPos center = region.getCenterChunk();
        if (center == null) {
            throw EMPTY_REGION.create();
        }

        final CompletableFuture<RegionProfile> future = new CompletableFuture<>();
        RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(
            level, center.x(), center.z(), () -> {
                try {
                    final RegionizedWorldData worldData = level.getCurrentWorldData();

                    int sections = 0;
                    final LongIterator iterator = region.getOwnedSectionsUnsynchronised();
                    while (iterator.hasNext()) {
                        iterator.nextLong();
                        sections++;
                    }

                    int tileEntities = 0;
                    for (final LevelChunk tickingChunk : worldData.getTickingChunks()) {
                        tileEntities += tickingChunk.getBlockEntitiesCount();
                    }

                    future.complete(new RegionProfile(
                        region.id,
                        sections,
                        worldData.getPlayerCount(),
                        worldData.getEntityCount(),
                        worldData.getChunkCount(),
                        tileEntities,
                        Math.max(0.0D, region.getData().getMSPT(RegionTickData.Frame._5_SECONDS))
                    ));
                } catch (final Throwable thrown) {
                    future.completeExceptionally(thrown);
                }
            },
            Priority.BLOCKING
        );

        try {
            final RegionProfile profile = future.get(5, TimeUnit.SECONDS);
            final RegionizerSettings settings = RegionizerSettings.get();
            final RegionizerLagPriority.Decision decision = RegionizerLagPriority.from(settings).decide(
                profile.id(),
                profile.players(),
                profile.chunks(),
                profile.mspt(),
                averageTickMillis()
            );

            Style.report("Region " + located.label())
                .bullet("World", level.dimension().identifier().getPath())
                .bullet("State", region.getState().name().toLowerCase(Locale.ROOT))
                .bullet("Sections", profile.sections())
                .bullet("Chunks", profile.chunks())
                .bullet("Players", profile.players())
                .bullet("Entities", profile.entities())
                .bullet("Tile entities", profile.tileEntities())
                .bullet("MSPT 5s", Style.mspt(profile.mspt()))
                .bullet("Load ratio", Style.value(decision.loadRatio()))
                .bullet("Density", Style.value(decision.density()))
                .bullet("Split factor", String.valueOf(decision.splitFactor()))
                .bullet("Priority", String.valueOf(decision.priority()))
                .bullet("Isolate", String.valueOf(decision.isolate()))
                .send(source);
            return Command.SINGLE_SUCCESS;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Style.fail(source, "Interrupted while reading region data");
            return 0;
        } catch (final Exception thrown) {
            Style.fail(source, "Could not read region data: " + thrown.getMessage());
            return 0;
        }
    }

    private static int sections(final CommandContext<CommandSourceStack> context, final @Nullable String selector) throws CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        final Located located = locate(source, selector);
        if (located.forwarded()) {
            throw FORWARDED_REGION.create();
        }

        final ServerLevel level = Objects.requireNonNull(located.level());
        final ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = Objects.requireNonNull(located.region());
        final int shift = level.regioniser.getSectionShift();

        final LongArrayList sectionKeys = region.getOwnedSections();
        final Map<Long, Integer> chunksPerSection = new HashMap<>();
        for (final long packedChunk : region.getOwnedChunks()) {
            final int sectionX = CoordinateUtils.getChunkX(packedChunk) >> shift;
            final int sectionZ = CoordinateUtils.getChunkZ(packedChunk) >> shift;
            chunksPerSection.merge(CoordinateUtils.getChunkKey(sectionX, sectionZ), 1, Integer::sum);
        }

        final List<Long> sorted = new ArrayList<>(sectionKeys.size());
        for (int i = 0; i < sectionKeys.size(); i++) {
            sorted.add(sectionKeys.getLong(i));
        }
        sorted.sort((first, second) -> {
            final int zCompare = Integer.compare(CoordinateUtils.getChunkZ(first), CoordinateUtils.getChunkZ(second));
            return zCompare != 0 ? zCompare : Integer.compare(CoordinateUtils.getChunkX(first), CoordinateUtils.getChunkX(second));
        });

        final Style.Report report = Style.report("Sections of region " + located.label());
        report.bullet("World", level.dimension().identifier().getPath())
            .bullet("Section shift", shift + " (1 section = " + (1 << shift) + "x" + (1 << shift) + " chunks)")
            .bullet("Sections", sectionKeys.size())
            .bullet("Sections with chunks", chunksPerSection.size())
            .bullet("Chunks", region.getOwnedPackedChunkPositions().length);

        final int shown = Math.min(MAX_SECTION_LINES, sorted.size());
        if (shown > 0) {
            report.gap();
            report.subHeader("Sections (section x, z)");
            for (int i = 0; i < shown; i++) {
                final long key = sorted.get(i);
                final int x = CoordinateUtils.getChunkX(key);
                final int z = CoordinateUtils.getChunkZ(key);
                report.line(Component.text()
                    .append(Component.text(" - ", Style.LIST, TextDecoration.BOLD))
                    .append(Component.text("(" + x + ", " + z + "): ", Style.PRIMARY))
                    .append(Style.value(chunksPerSection.getOrDefault(key, 0)))
                    .append(Component.text(" chunks", Style.SECONDARY))
                    .build());
            }
            if (sorted.size() > shown) {
                report.bullet("More", (sorted.size() - shown) + " sections not shown");
            }
        }

        report.send(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int at(final CommandContext<CommandSourceStack> context, final @Nullable ServerLevel dimension) {
        final CommandSourceStack source = context.getSource();
        final int blockX = IntegerArgumentType.getInteger(context, "x");
        final int blockZ = IntegerArgumentType.getInteger(context, "z");
        final ServerLevel level = dimension != null ? dimension : source.getLevel();
        final ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = level.regioniser.getRegionAtUnsynchronised(blockX >> 4, blockZ >> 4);

        if (region == null) {
            Style.fail(source, "No region exists at " + blockX + ", " + blockZ + " in "
                + level.dimension().identifier().getPath());
            return 0;
        }

        final RegionNumbering.Entry entry = entryById(RegionNumbering.list(source.getServer()), region.id);
        final String label = entry == null ? "id=" + region.id : "#" + entry.number() + " (id=" + region.id + ")";

        Style.report("Region at " + blockX + ", " + blockZ)
            .bullet("Region", label)
            .bullet("World", level.dimension().identifier().getPath())
            .bullet("Chunk", (blockX >> 4) + ", " + (blockZ >> 4))
            .bullet("State", region.getState().name().toLowerCase(Locale.ROOT))
            .bullet("Sections", region.getOwnedSections().size())
            .bullet("Chunks", region.getOwnedPackedChunkPositions().length)
            .bullet("MSPT 5s", Style.mspt(Math.max(0.0D, region.getData().getMSPT(RegionTickData.Frame._5_SECONDS))))
            .send(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int players(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final MinecraftServer server = source.getServer();
        final List<ServerPlayer> online = server.getPlayerList().getPlayers();

        final Map<Long, Integer> perRegion = new HashMap<>();
        int withoutRegion = 0;
        for (final ServerPlayer player : online) {
            final ServerLevel level = (ServerLevel) player.level();
            final ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = level.regioniser.getRegionAtUnsynchronised(player.getBlockX() >> 4, player.getBlockZ() >> 4);
            if (region == null) {
                withoutRegion++;
            } else {
                perRegion.merge(region.id, 1, Integer::sum);
            }
        }

        final Style.Report report = Style.report("Players by region");
        report.bullet("Online", online.size());
        if (perRegion.isEmpty()) {
            report.bullet("Regions with players", Component.text("none", Style.SECONDARY));
        } else {
            for (final RegionNumbering.Entry entry : RegionNumbering.list(server)) {
                final Integer count = perRegion.get(entry.regionId());
                if (count == null) {
                    continue;
                }
                report.bullet("#" + entry.number() + " (id=" + entry.regionId() + ") " + entry.worldPath(),
                    count + " player" + (count == 1 ? "" : "s"));
            }
        }
        report.bullet("Without a region", withoutRegion);
        report.send(source);
        return Command.SINGLE_SUCCESS;
    }

    private static int entities(
        final CommandContext<CommandSourceStack> context,
        final @Nullable String selector,
        final @Nullable String typeFilter
    ) throws CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        final Located located = locate(source, selector);
        if (located.forwarded()) {
            throw FORWARDED_REGION.create();
        }

        final ServerLevel level = Objects.requireNonNull(located.level());
        final ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = Objects.requireNonNull(located.region());
        final ChunkPos center = region.getCenterChunk();
        if (center == null) {
            throw EMPTY_REGION.create();
        }

        final String filter = typeFilter == null ? null : typeFilter.toLowerCase(Locale.ROOT);
        final CompletableFuture<Map<String, Integer>> future = new CompletableFuture<>();

        RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(
            level, center.x(), center.z(), () -> {
                try {
                    final Map<String, Integer> result = new HashMap<>();
                    final RegionizedWorldData worldData = level.getCurrentWorldData();
                    for (final Entity entity : worldData.trackerEntities) {
                        final String name = EntityType.getKey(entity.getType()).toString();
                        if (filter != null && !name.toLowerCase(Locale.ROOT).contains(filter)) {
                            continue;
                        }
                        result.merge(name, 1, Integer::sum);
                    }
                    future.complete(result);
                } catch (final Throwable thrown) {
                    future.completeExceptionally(thrown);
                }
            },
            Priority.BLOCKING
        );

        try {
            final Map<String, Integer> result = future.get(5, TimeUnit.SECONDS);
            final List<Map.Entry<String, Integer>> sorted = new ArrayList<>(result.entrySet());
            sorted.sort((first, second) -> Integer.compare(second.getValue(), first.getValue()));

            final Style.Report report = Style.report(
                (filter == null ? "Entities" : "Entities matching '" + filter + "'") + " in region " + located.label());
            report.bullet("Distinct types", result.size());
            if (sorted.isEmpty()) {
                report.bullet("Entities", Component.text("none", Style.SECONDARY));
            } else {
                for (final Map.Entry<String, Integer> entry : sorted) {
                    report.bullet(entry.getKey(), entry.getValue());
                }
            }
            report.send(source);
            return Command.SINGLE_SUCCESS;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Style.fail(source, "Interrupted while reading entities");
            return 0;
        } catch (final Exception thrown) {
            Style.fail(source, "Could not read entities: " + thrown.getMessage());
            return 0;
        }
    }

    private static int decisions(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final MinecraftServer server = source.getServer();
        final RegionizerSettings settings = RegionizerSettings.get();
        final double globalMspt = averageTickMillis();
        final RegionizerLagPriority policy = RegionizerLagPriority.from(settings);
        final Map<Long, LiveSnapshot> live = snapshot(server);
        final Map<Long, Integer> perRegion = new HashMap<>();
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            final ServerLevel level = (ServerLevel) player.level();
            final ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = level.regioniser.getRegionAtUnsynchronised(player.getBlockX() >> 4, player.getBlockZ() >> 4);
            if (region != null) {
                perRegion.merge(region.id, 1, Integer::sum);
            }
        }

        final Style.Report report = Style.report("Regionizer decisions");
        report.bullet("Split enabled", Style.onOff(settings.enabled()));
        report.bullet("Split high load MSPT", String.valueOf(settings.splitHighLoadMspt()));
        report.bullet("Split max sections", settings.splitMaxSections());
        report.bullet("Split min size", settings.splitMinSize());
        report.bullet("Global MSPT", Style.mspt(globalMspt));
        report.gap();

        boolean any = false;
        for (final RegionNumbering.Entry entry : RegionNumbering.list(server)) {
            final LiveSnapshot snapshot = live.get(entry.regionId());
            if (snapshot == null) {
                continue;
            }
            any = true;
            final RegionizerLagPriority.Decision decision = policy.decide(
                entry.regionId(),
                perRegion.getOrDefault(entry.regionId(), 0),
                entry.chunks(),
                snapshot.mspt(),
                globalMspt
            );
            report.line(Component.text()
                .append(Component.text(" - ", Style.LIST, TextDecoration.BOLD))
                .append(Component.text("#" + entry.number() + " (id=" + entry.regionId() + ") ", Style.PRIMARY))
                .append(Component.text(entry.worldPath() + ": ", Style.SECONDARY))
                .append(Style.mspt(snapshot.mspt()))
                .append(Component.text(", ", Style.SECONDARY))
                .append(Style.value(decision.loadRatio()))
                .append(Component.text(" load, priority ", Style.SECONDARY))
                .append(Style.value(String.valueOf(decision.priority())))
                .build());
        }

        if (!any) {
            report.bullet("Regions", Component.text("none active", Style.SECONDARY));
        }
        report.send(source);
        return Command.SINGLE_SUCCESS;
    }

    private static Map<Long, LiveSnapshot> snapshot(final MinecraftServer server) {
        final Map<Long, LiveSnapshot> live = new HashMap<>();
        for (final ServerLevel level : server.getAllLevels()) {
            level.regioniser.computeForAllRegions(region -> live.put(
                region.id,
                new LiveSnapshot(
                    region.getState(),
                    Math.max(0.0D, region.getData().getMSPT(RegionTickData.Frame._5_SECONDS))
                )
            ));
        }
        return live;
    }

    private static Component renderRow(final RegionNumbering.Entry entry, final @Nullable LiveSnapshot live) {
        final Component row = Component.text()
            .append(Component.text(" - ", Style.LIST, TextDecoration.BOLD))
            .append(Component.text("#" + entry.number() + " (id=" + entry.regionId() + ")", Style.PRIMARY))
            .append(Component.text(" " + entry.worldPath() + ": ", Style.SECONDARY))
            .append(Style.value(entry.sections()))
            .append(Component.text(" sections, ", Style.SECONDARY))
            .append(Style.value(entry.chunks()))
            .append(Component.text(" chunks", Style.SECONDARY))
            .build();

        if (entry.forwarded()) {
            return Component.text()
                .append(row)
                .append(Component.text(", forwarded to Compute Host ", Style.SECONDARY))
                .append(Style.value(entry.forwardedToHost().longValue()))
                .build();
        }

        if (live == null) {
            return row;
        }

        final boolean ticking = live.state() == WorldRegionizer.ChunkRegion.State.TICKING;
        return Component.text()
            .append(row)
            .append(Component.text(", ", Style.SECONDARY))
            .append(Component.text(
                ticking ? "ticking" : live.state().name().toLowerCase(Locale.ROOT),
                ticking ? Style.good() : Style.SECONDARY
            ))
            .append(Component.text(", ", Style.SECONDARY))
            .append(Style.mspt(live.mspt()))
            .build();
    }

    private static Located locate(final CommandSourceStack source, final @Nullable String selector) throws CommandSyntaxException {
        final MinecraftServer server = source.getServer();

        if (selector == null || selector.equalsIgnoreCase("here")) {
            final ServerLevel level = source.getLevel();
            final ChunkPos chunk = sourceChunk(source);
            final ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = level.regioniser.getRegionAtUnsynchronised(chunk.x(), chunk.z());
            if (region == null) {
                throw NO_REGION_AT_SOURCE.create();
            }
            return new Located(entryById(RegionNumbering.list(server), region.id), level, region);
        }

        final RegionNumbering.Entry entry = entryFor(server, selector);
        if (entry.forwarded()) {
            return new Located(entry, null, null);
        }

        for (final ServerLevel level : server.getAllLevels()) {
            final ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = regionById(level, entry.regionId());
            if (region != null) {
                return new Located(entry, level, region);
            }
        }
        throw UNKNOWN_REGION.create();
    }

    private static RegionNumbering.Entry entryFor(final MinecraftServer server, final String selector) throws CommandSyntaxException {
        final List<RegionNumbering.Entry> entries = RegionNumbering.list(server);

        if (selector.startsWith("#")) {
            final int number;
            try {
                number = Integer.parseInt(selector.substring(1));
            } catch (final NumberFormatException thrown) {
                throw INVALID_REGION.create();
            }
            if (number < 1 || number > entries.size()) {
                throw UNKNOWN_REGION.create();
            }
            return entries.get(number - 1);
        }

        final long id;
        try {
            id = Long.parseLong(selector);
        } catch (final NumberFormatException thrown) {
            throw INVALID_REGION.create();
        }
        for (final RegionNumbering.Entry entry : entries) {
            if (entry.regionId() == id) {
                return entry;
            }
        }
        throw UNKNOWN_REGION.create();
    }

    private static RegionNumbering.@Nullable Entry entryById(final List<RegionNumbering.Entry> entries, final long id) {
        for (final RegionNumbering.Entry entry : entries) {
            if (entry.regionId() == id) {
                return entry;
            }
        }
        return null;
    }

    private static @Nullable ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> regionById(final ServerLevel level, final long id) {
        final AtomicReference<ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> found = new AtomicReference<>();
        level.regioniser.computeForAllRegions(region -> {
            if (region.id == id) {
                found.set(region);
            }
        });
        return found.get();
    }

    private static ChunkPos sourceChunk(final CommandSourceStack source) {
        final ServerPlayer player = source.getPlayer();
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

    private static CompletableFuture<Suggestions> suggestRegions(
        final CommandContext<CommandSourceStack> context,
        final SuggestionsBuilder builder
    ) {
        builder.suggest("here");
        for (final RegionNumbering.Entry entry : RegionNumbering.list(context.getSource().getServer())) {
            builder.suggest("#" + entry.number());
        }
        return builder.buildFuture();
    }


    private static int forceUnload(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final String selector = StringArgumentType.getString(context, "region");
        final Located located = locate(context.getSource(), selector);
        if (located.forwarded()) {
            throw FORWARDED_REGION.create();
        }
        if (located.region() == null) {
            throw UNKNOWN_REGION.create();
        }
        return ForceUnloadSubCommand.execute(context, located.region().id);
    }

    private static CompletableFuture<Suggestions> suggestEntityTypes(
        final CommandContext<CommandSourceStack> context,
        final SuggestionsBuilder builder
    ) {
        for (final EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            builder.suggest(EntityType.getKey(type).toString());
        }
        return builder.buildFuture();
    }

    private record LiveSnapshot(WorldRegionizer.ChunkRegion.State state, double mspt) {
    }

    private record Located(
        RegionNumbering.@Nullable Entry entry,
        @Nullable ServerLevel level,
        @Nullable ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region
    ) {
        boolean forwarded() {
            return this.entry != null && this.entry.forwarded();
        }

        String label() {
            if (this.entry != null) {
                return "#" + this.entry.number() + " (id=" + this.entry.regionId() + ")";
            }
            return this.region == null ? "unknown" : "id=" + this.region.id;
        }
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
    }
}




