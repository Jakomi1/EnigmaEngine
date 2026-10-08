package io.canvasmc.canvas.distributed.command;

import ca.spottedleaf.common.time.TickData;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.canvasmc.canvas.commands.Style;
import io.canvasmc.canvas.commands.SubCommand;
import io.canvasmc.canvas.distributed.DistributedBootstrap;
import io.canvasmc.canvas.distributed.config.EnigmaDistributedConfig;
import io.canvasmc.canvas.distributed.network.RuntimeConnection;
import io.canvasmc.canvas.distributed.runtime.RemoteRegionExecutor;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.TickRegions;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class DistributedSubCommand implements SubCommand {

    /**
     * Cap for the per-region "forwarded to Compute Host X" sub-lines in the status report so
     * a long-running World Host cannot turn the status into a wall of text.
     */
    private static final int MAX_FORWARDED_STATUS_LINES = 10;

    @Override
    public @Nullable String getDescription() {
        return "Manage the EnigmaEngine distributed runtime";
    }

    @Override
    public String getName() {
        return "distributed";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(
            final LiteralArgumentBuilder<CommandSourceStack> base,
            final CommandBuildContext buildContext
    ) {
        return base
                // Bare "/enigma distributed" would be an "Unknown or incomplete command" error
                // without an executes on the literal itself; show the status instead.
                .executes(this::status)
                .then(literal("status")
                        .requires(this.check("status"))
                        .executes(this::status))
                .then(literal("migrate")
                        .requires(this.check("migrate"))
                        .executes(this::migrateAggressive)
                        .then(argument("regionId", LongArgumentType.longArg())
                                .executes(this::migrateSelf))
                        .then(argument("regionId", LongArgumentType.longArg())
                                .then(argument("targetInstanceId", LongArgumentType.longArg())
                                        .executes(this::migrate))))
                .then(literal("gc")
                        .requires(this.check("gc"))
                        .executes(this::gc))
                .then(literal("info")
                        .requires(this.check("info"))
                        // No argument: list the regions with their display numbers so the
                        // argument form has something to refer to.
                        .executes(this::regions)
                        .then(argument("regionId", LongArgumentType.longArg())
                                .executes(this::info)))
                .then(literal("regions")
                        .requires(this.check("regions"))
                        .executes(this::regions))
                .then(literal("migrations")
                        .requires(this.check("migrations"))
                        .executes(this::migrations))
                .then(literal("prepare-stop")
                        .requires(this.check("prepare-stop"))
                        .executes(this::prepareStop))
                .then(literal("debug")
                        .requires(this.check("debug"))
                        .then(literal("block")
                                .then(argument("x", IntegerArgumentType.integer())
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .executes(this::debugBlockRead)
                                                        .then(argument("block", StringArgumentType.string())
                                                                .executes(this::debugBlockWrite))))))
                        .then(literal("chest")
                                .then(argument("x", IntegerArgumentType.integer())
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .executes(this::debugChestSet))))));
    }

    private int status(final CommandContext<CommandSourceStack> ctx) {
        final DistributedBootstrap bootstrap = DistributedBootstrap.getInstance();
        final EnigmaDistributedConfig config = bootstrap.getConfig();
        final CommandSourceStack source = ctx.getSource();

        final Style.Report report = Style.report("Enigma Distributed Status");
        report.bullet("Config source", "-Denigma.distributed.* system properties");
        report.bullet("Enabled", Style.onOff(config.enabled));

        if (!config.enabled) {
            report.send(source);
            return 1;
        }

        final EnigmaDistributedConfig.Role role = config.getRole();
        report.bullet("Role", role == null ? "<unset>" : role.name());
        report.bullet("Instance ID", String.valueOf(bootstrap.getIdentity().getInstanceId()));
        report.bullet("Started", Style.onOff(bootstrap.isStarted()));
        report.bullet("Port", String.valueOf(config.port));
        report.bullet("World host", config.worldHostAddress + ":" + config.effectiveWorldHostPort()
                + (config.worldHostPort != config.effectiveWorldHostPort()
                    ? " (worldHostPort=" + config.worldHostPort + ")" : ""));

        final RuntimeConnection connection = bootstrap.getRuntimeConnection();
        if (connection == null || !connection.isOpen()) {
            report.bullet("Control channel", Component.text("<not connected>", Style.bad()));
        } else if (connection.isListener()) {
            // A listening channel has no peer, so remoteAddress() is null.
            report.bullet("Control channel", Component.text()
                    .append(Component.text("listening on ", Style.good()))
                    .append(Component.text(String.valueOf(connection.getChannel().localAddress()), Style.INFORMATION))
                    .build());
        } else {
            report.bullet("Control channel", Component.text()
                    .append(Component.text("connected to ", Style.good()))
                    .append(Component.text(String.valueOf(connection.getChannel().remoteAddress()), Style.INFORMATION))
                    .build());
        }

        final RemoteRegionExecutor executor = bootstrap.getRegionExecutor();
        if (executor != null) {
            report.bullet("Active regions", String.valueOf(executor.activeRegionCount()));
            final java.util.List<RegionNumbering.Entry> entries = RegionNumbering.list(MinecraftServer.getServer());
            for (final var entry : executor.getActiveRegions().entrySet()) {
                final var region = entry.getValue();
                report.line(Component.text()
                        .append(Component.text("    ", Style.PRIMARY))
                        .append(Component.text(RegionNumbering.label(entries, entry.getKey()), Style.INFORMATION, TextDecoration.BOLD))
                        .append(Component.text(": gen=", Style.PRIMARY))
                        .append(Style.value(region.generation))
                        .append(Component.text(", ", Style.SECONDARY))
                        .append(Style.value(region.ownedChunkCount()))
                        .append(Component.text(" chunks, scheduled=", Style.SECONDARY))
                        .append(Style.onOff(region.tickHandle != null))
                        .build());
            }
        }

        report.bullet("Active leases", String.valueOf(bootstrap.getLeaseManager().activeLeaseCount()));

        report.bullet("Auto migration", Component.text()
                .append(Style.onOff(config.autoMigrate))
                .append(config.autoMigrate
                    ? Component.text(" (every " + config.autoMigrateIntervalSeconds + "s, up to "
                        + config.autoMigratePerRun + " new, max " + config.autoMigrateMaxConcurrent
                        + " concurrent, min " + config.autoMigrateMinChunks + " chunks)", Style.SECONDARY)
                    : Component.empty())
                .build());

        if (executor != null) {
            final int activeMigrations = executor.activeMigrationCount();
            final int migrationRecords = executor.listMigrations().size();
            report.bullet("Migrations", Component.text()
                    .append(Style.value(activeMigrations))
                    .append(Component.text(" active, ", Style.SECONDARY))
                    .append(Style.value(migrationRecords - activeMigrations))
                    .append(Component.text(" in history", Style.SECONDARY))
                    .build());

            if (role == EnigmaDistributedConfig.Role.WORLD_HOST) {
                final java.util.List<RemoteRegionExecutor.ForwardedRegion> forwarded = executor.forwardedRegions();
                report.bullet("Handed over regions", Component.text()
                        .append(Style.value(executor.handedOverCount()))
                        .append(Component.text(" (", Style.SECONDARY))
                        .append(Style.value(executor.handedOverPendingRelease()))
                        .append(Component.text(" pending release)", executor.handedOverPendingRelease() > 0
                            ? Style.warn() : Style.SECONDARY))
                        .build());
                // One sub-line per forwarded region: the "which region lives on which
                // server" view.
                final int shown = Math.min(forwarded.size(), MAX_FORWARDED_STATUS_LINES);
                for (int i = 0; i < shown; i++) {
                    report.line(forwardedLine(forwarded.get(i)));
                }
                if (forwarded.size() > shown) {
                    report.line(Component.text()
                            .append(Component.text("    ", Style.PRIMARY))
                            .append(Component.text("… and " + (forwarded.size() - shown) + " more", Style.SECONDARY))
                            .build());
                }
            } else if (executor.installedRegionCount() > 0) {
                report.bullet("Installed from World Host", Component.text()
                        .append(Style.value(executor.installedRegionCount()))
                        .append(Component.text(" region(s)", Style.SECONDARY))
                        .build());
            }
        }

        if (role == EnigmaDistributedConfig.Role.WORLD_HOST) {
            final var peers = bootstrap.getComputeHostConnections();
            report.bullet("Compute Hosts", String.valueOf(peers.size()));
            for (final RuntimeConnection peer : peers) {
                report.line(Component.text()
                        .append(Component.text("    ", Style.PRIMARY))
                        .append(Style.value(String.valueOf(peer.getRemoteInstanceId())))
                        .append(Component.text(" from ", Style.SECONDARY))
                        .append(Style.value(String.valueOf(peer.getChannel().remoteAddress())))
                        .build());
            }
        }
        report.send(source);
        return 1;
    }

    /**
     * {@code     #4 (id=3) → Compute Host 123 · 2,148 chunks · released}
     */
    private static Component forwardedLine(final RemoteRegionExecutor.ForwardedRegion forwarded) {
        final java.util.List<RegionNumbering.Entry> entries =
                RegionNumbering.list(MinecraftServer.getServer());
        return Component.text()
                .append(Component.text("    ", Style.PRIMARY))
                .append(Component.text(RegionNumbering.label(entries, forwarded.regionId()), Style.INFORMATION, TextDecoration.BOLD))
                .append(Component.text(" → Compute Host ", Style.PRIMARY))
                .append(Style.value(String.valueOf(forwarded.targetInstanceId())))
                .append(Component.text(" · ", Style.SECONDARY))
                .append(Style.value(forwarded.chunkCount()))
                .append(Component.text(" chunks", Style.SECONDARY))
                .append(Component.text(" · ", Style.SECONDARY))
                .append(forwarded.released()
                    ? Component.text("released", Style.good())
                    : Component.text("releasing", Style.warn()))
                .build();
    }

    /**
     * The most recent migration record for a region, or null when it never migrated (or the
     * record already aged out of the 60s history window).
     */
    private static RemoteRegionExecutor.@Nullable MigrationView latestMigration(
            final RemoteRegionExecutor executor,
            final long regionId
    ) {
        RemoteRegionExecutor.MigrationView latest = null;
        for (final RemoteRegionExecutor.MigrationView view : executor.listMigrations()) {
            if (view.regionId() == regionId
                    && (latest == null || view.startedAtMs() > latest.startedAtMs())) {
                latest = view;
            }
        }
        return latest;
    }

    private static TextColor stageColour(final RemoteRegionExecutor.MigrationStage stage) {
        return switch (stage) {
            case VERIFIED -> Style.good();
            case FAILED -> Style.bad();
            default -> Style.SECONDARY;
        };
    }

    private int migrateSelf(final CommandContext<CommandSourceStack> ctx) {
        final java.util.OptionalLong resolved =
                resolveRegionArgument(ctx, LongArgumentType.getLong(ctx, "regionId"));
        return resolved.isPresent() ? migrate(ctx, resolved.getAsLong()) : 0;
    }

    /**
     * No-argument variant: garbage collects empty regions first, then migrates the largest remaining
     * overworld region so there is always something big and visible to hand over.
     */
    private int migrateAggressive(final CommandContext<CommandSourceStack> ctx) {
        final DistributedBootstrap bootstrap = DistributedBootstrap.getInstance();
        if (!bootstrap.isStarted()) {
            Style.fail(ctx.getSource(), "Distributed runtime is not started");
            return 0;
        }
        if (!bootstrap.getConfig().isWorldHost()) {
            Style.fail(ctx.getSource(), "Only the World Host can migrate regions; it owns the region data");
            return 0;
        }
        final RemoteRegionExecutor executor = bootstrap.getRegionExecutor();
        if (executor == null) {
            Style.fail(ctx.getSource(), "Region executor is not available");
            return 0;
        }

        final int released = executor.garbageCollectRegions();
        gcFeedback(ctx.getSource(), released);

        final List<long[]> ranked = executor.topRegionsByChunks(1);
        if (ranked.isEmpty()) {
            Style.fail(ctx.getSource(), "No non-empty region left to migrate");
            return 0;
        }
        final long regionId = ranked.get(0)[0];
        final long chunks = ranked.get(0)[1];
        final String label = RegionNumbering.label(RegionNumbering.list(MinecraftServer.getServer()), regionId);
        Style.bullets()
                .bullet("Migration target", Component.text()
                        .append(Component.text("largest region ", Style.INFORMATION))
                        .append(Component.text(label, Style.INFORMATION, TextDecoration.BOLD))
                        .append(Component.text(" (", Style.SECONDARY))
                        .append(Style.value(chunks))
                        .append(Component.text(" chunks)", Style.SECONDARY))
                        .build())
                .send(ctx.getSource());
        return migrate(ctx, regionId);
    }

    private int gc(final CommandContext<CommandSourceStack> ctx) {
        final DistributedBootstrap bootstrap = DistributedBootstrap.getInstance();
        if (!bootstrap.isStarted()) {
            Style.fail(ctx.getSource(), "Distributed runtime is not started");
            return 0;
        }
        final RemoteRegionExecutor executor = bootstrap.getRegionExecutor();
        if (executor == null) {
            Style.fail(ctx.getSource(), "Region executor is not available");
            return 0;
        }
        gcFeedback(ctx.getSource(), executor.garbageCollectRegions());
        return 1;
    }

    private static void gcFeedback(final CommandSourceStack source, final int released) {
        Style.bullets()
                .bullet("Region GC", Component.text()
                        .append(Component.text("released ", Style.INFORMATION))
                        .append(Style.value(released))
                        .append(Component.text(" empty region(s)", Style.INFORMATION))
                        .build())
                .send(source);
    }

    private int prepareStop(final CommandContext<CommandSourceStack> ctx) {
        return executePrepareStop(ctx);
    }

    /**
     * Shared by {@code /enigma distributed prepare-stop} and the root {@code /prepare-stop} alias:
     * returns every forwarded and recovering region to the World Host, then stops the server.
     */
    public static int executePrepareStop(final CommandContext<CommandSourceStack> ctx) {
        final DistributedBootstrap bootstrap = DistributedBootstrap.getInstance();
        if (!bootstrap.isStarted()) {
            Style.fail(ctx.getSource(), "Distributed runtime is not started");
            return 0;
        }
        if (!bootstrap.getConfig().isWorldHost()) {
            Style.fail(ctx.getSource(), "Only the World Host can prepare a stop; it owns the region data");
            return 0;
        }
        final RemoteRegionExecutor executor = bootstrap.getRegionExecutor();
        final int forwarded = executor == null ? 0 : executor.handedOverCount();
        final int recovering = executor == null ? 0 : executor.recoveringRegionCount();
        if (executor == null || (forwarded == 0 && recovering == 0)) {
            Style.fail(ctx.getSource(), "No regions are currently out on Compute Hosts; stopping directly");
            MinecraftServer.getServer().halt(false);
            return 1;
        }
        Style.bullets()
                .bullet("Prepare stop", Component.text()
                        .append(Component.text("returning ", Style.INFORMATION))
                        .append(Style.value(forwarded))
                        .append(Component.text(" forwarded and ", Style.INFORMATION))
                        .append(Style.value(recovering))
                        .append(Component.text(" recovering region(s) to the World Host, then stopping", Style.INFORMATION))
                        .build())
                .send(ctx.getSource());
        bootstrap.prepareStop();
        return 1;
    }

    private int migrate(final CommandContext<CommandSourceStack> ctx) {
        final java.util.OptionalLong resolved =
                resolveRegionArgument(ctx, LongArgumentType.getLong(ctx, "regionId"));
        return resolved.isPresent() ? migrate(ctx, resolved.getAsLong()) : 0;
    }

    private int migrate(final CommandContext<CommandSourceStack> ctx, final long regionId) {
        final DistributedBootstrap bootstrap = DistributedBootstrap.getInstance();
        if (!bootstrap.isStarted()) {
            Style.fail(ctx.getSource(), "Distributed runtime is not started");
            return 0;
        }
        if (!bootstrap.getConfig().isWorldHost()) {
            Style.fail(ctx.getSource(), "Only the World Host can migrate regions; it owns the region data");
            return 0;
        }

        final long targetInstanceId;
        final java.util.OptionalLong explicit = explicitTarget(ctx);
        if (explicit.isPresent()) {
            targetInstanceId = explicit.getAsLong();
        } else {
            // No explicit target: pick the only connected Compute Host.
            final var peers = bootstrap.getComputeHostConnections();
            if (peers.size() != 1) {
                Style.fail(ctx.getSource(), "Expected exactly one connected Compute Host but found "
                        + peers.size() + ". Specify one explicitly: /enigma distributed migrate <regionId> <instanceId>");
                return 0;
            }
            targetInstanceId = peers.iterator().next().getRemoteInstanceId();
        }

        final String failure = bootstrap.startMigration(regionId, targetInstanceId);
        if (failure != null) {
            Style.fail(ctx.getSource(), "Cannot migrate region "
                    + RegionNumbering.label(RegionNumbering.list(MinecraftServer.getServer()), regionId)
                    + ": " + failure);
            return 0;
        }
        Style.bullets()
                .bullet("Migration", Component.text()
                        .append(Component.text(RegionNumbering.label(RegionNumbering.list(MinecraftServer.getServer()), regionId),
                                Style.INFORMATION, TextDecoration.BOLD))
                        .append(Component.text(" → Compute Host ", Style.PRIMARY))
                        .append(Style.value(String.valueOf(targetInstanceId)))
                        .append(Component.text(" started", Style.good()))
                        .build())
                .send(ctx.getSource());
        return 1;
    }

    /**
     * The commands print display numbers ({@code #N}), so arguments are resolved through
     * {@link RegionNumbering} before they are treated as real region ids. Prints the failure
     * itself; callers just return 0 when the result is empty.
     */
    private static java.util.OptionalLong resolveRegionArgument(
            final CommandContext<CommandSourceStack> ctx,
            final long argument
    ) {
        final java.util.OptionalLong resolved =
                RegionNumbering.resolve(MinecraftServer.getServer(), argument);
        if (resolved.isEmpty()) {
            Style.fail(ctx.getSource(), "No region matches '" + argument
                    + "'. List regions with /enigma distributed regions");
        }
        return resolved;
    }

    private int info(final CommandContext<CommandSourceStack> ctx) {
        final DistributedBootstrap bootstrap = DistributedBootstrap.getInstance();
        if (!bootstrap.isStarted()) {
            Style.fail(ctx.getSource(), "Distributed runtime is not started");
            return 0;
        }

        final java.util.OptionalLong resolved =
                resolveRegionArgument(ctx, LongArgumentType.getLong(ctx, "regionId"));
        if (resolved.isEmpty()) {
            return 0;
        }
        final long regionId = resolved.getAsLong();
        final MinecraftServer server = MinecraftServer.getServer();
        final java.util.List<RegionNumbering.Entry> entries = RegionNumbering.list(server);
        final RemoteRegionExecutor executor = bootstrap.getRegionExecutor();

        // Forwarded regions are gone from the local regionizer; report where they went.
        for (final RegionNumbering.Entry entry : entries) {
            if (entry.regionId() != regionId || !entry.forwarded()) {
                continue;
            }
            final Style.Report report = Style.report(Component.text()
                    .append(Component.text("Region " + RegionNumbering.label(entries, regionId) + " ", Style.HEADER, TextDecoration.BOLD))
                    .append(Component.text("→ Compute Host " + entry.forwardedToHost(), Style.good(), TextDecoration.BOLD))
                    .build());
            report.bullet("World", entry.worldPath());
            report.bullet("Chunks", entry.chunks());
            if (executor != null) {
                final RemoteRegionExecutor.MigrationView view = latestMigration(executor, regionId);
                if (view != null) {
                    report.bullet("Migration", migrationValue(view));
                }
            }
            report.send(ctx.getSource());
            return 1;
        }

        for (final ServerLevel level : server.getAllLevels()) {
            final ThreadedRegionizer<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> regionizer = level.regioniser;
            final var found = new java.util.ArrayList<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>>(1);
            regionizer.computeForAllRegions(region -> {
                if (region.id == regionId) {
                    found.add(region);
                }
            });
            if (found.isEmpty()) {
                continue;
            }

            final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = found.get(0);
            final Style.Report report = Style.report("Region " + RegionNumbering.label(entries, regionId));
            report.bullet("World", level.dimension().identifier().getPath());
            report.bullet("Chunks", region.getOwnedPackedChunkPositions().length);
            report.bullet("Sections", region.getOwnedSections().size());

            // Same 15s tick report /tps shows for a region.
            final TickData.TickReportData tickReport =
                    region.getData().getRegionSchedulingHandle().getTickReport15s(System.nanoTime());
            if (tickReport != null) {
                report.bullet("TPS 15s", Style.tps(tickReport.tpsData().segmentAll().average()));
                report.bullet("MSPT 15s", Style.mspt(tickReport.timePerTickData().segmentAll().average() / 1.0E6));
                report.bullet("Utilisation", Style.util(tickReport.utilisation()));
            }

            final TickRegions.RegionStats stats = region.getData().getRegionStats();
            report.bullet("Players", stats.getPlayerCount());
            report.bullet("Entities", stats.getEntityCount());
            report.bullet("Generation", bootstrap.getGenerationManager().currentGeneration(regionId));

            final var lease = bootstrap.getLeaseManager().getLease(regionId);
            report.bullet("Lease", lease.isPresent()
                    ? Style.value(String.valueOf(lease.get()))
                    : Component.text("none", Style.SECONDARY));

            if (executor != null) {
                if (executor.isInstalledLocally(regionId)) {
                    report.bullet("Origin", Component.text("migrated in from the World Host", Style.good()));
                }
                final RemoteRegionExecutor.MigrationView view = latestMigration(executor, regionId);
                if (view != null) {
                    report.bullet("Migration", migrationValue(view));
                }
            }
            report.send(ctx.getSource());
            return 1;
        }

        Style.fail(ctx.getSource(), "Region " + RegionNumbering.label(entries, regionId)
                + " not found in any level");
        return 0;
    }

    /**
     * {@code VERIFIED, age 45s, chunks 26/26} - the value part shared by the {@code info} and
     * {@code migrations} output.
     */
    private static Component migrationValue(final RemoteRegionExecutor.MigrationView view) {
        final long now = System.currentTimeMillis();
        final var builder = Component.text()
                .append(Component.text(view.stage().name(), stageColour(view.stage())))
                .append(Component.text(", age ", Style.SECONDARY))
                .append(Component.text(((now - view.startedAtMs()) / 1000L) + "s", Style.INFORMATION));
        if (view.progressTotal() > 0) {
            builder.append(Component.text(", chunks ", Style.SECONDARY))
                    .append(Component.text(view.progressSent() + "/" + view.progressTotal(), Style.INFORMATION));
        }
        if (view.failure() != null) {
            builder.append(Component.text(", failure: ", Style.SECONDARY))
                    .append(Component.text(view.failure(), Style.bad()));
        }
        return builder.build();
    }

    /**
     * Region ids are per process and are not derivable, so there is no way to pick one for
     * {@code migrate} without listing them first. The {@code #N} prefix is what the other
     * commands accept; the real id is shown for log correlation. Forwarded regions keep their
     * row with the Compute Host they now live on.
     */
    private int regions(final CommandContext<CommandSourceStack> ctx) {
        final MinecraftServer server = MinecraftServer.getServer();
        final java.util.List<RegionNumbering.Entry> entries = RegionNumbering.list(server);
        final RemoteRegionExecutor executor = DistributedBootstrap.getInstance().getRegionExecutor();
        final Style.Report report = Style.report("Regions");

        if (entries.isEmpty()) {
            report.bullet("Total", Component.text()
                    .append(Style.value(0L))
                    .append(Component.text(" regions", Style.SECONDARY))
                    .build());
            report.send(ctx.getSource());
            return 0;
        }

        final java.util.Map<String, java.util.List<RegionNumbering.Entry>> byWorld =
                new java.util.LinkedHashMap<>();
        for (final RegionNumbering.Entry entry : entries) {
            byWorld.computeIfAbsent(entry.worldPath(), world -> new java.util.ArrayList<>()).add(entry);
        }

        int total = 0;
        int forwardedTotal = 0;
        for (final ServerLevel level : server.getAllLevels()) {
            final String worldPath = level.dimension().identifier().getPath();
            final java.util.List<RegionNumbering.Entry> list =
                    byWorld.getOrDefault(worldPath, java.util.List.of());
            if (list.isEmpty()) {
                continue;
            }
            report.line(Component.text()
                    .append(Component.text(" - ", Style.LIST, TextDecoration.BOLD))
                    .append(Component.text(worldPath + ":", Style.PRIMARY, TextDecoration.BOLD))
                    .build());
            for (final RegionNumbering.Entry entry : list) {
                report.line(regionRow(entry, executor));
                total++;
                if (entry.forwarded()) {
                    forwardedTotal++;
                }
            }
        }

        final int grandTotal = total;
        final int grandForwarded = forwardedTotal;
        report.bullet("Total", Component.text()
                .append(Style.value(grandTotal))
                .append(Component.text(" regions (", Style.SECONDARY))
                .append(Style.value(grandTotal - grandForwarded))
                .append(Component.text(" local, ", Style.SECONDARY))
                .append(Style.value(grandForwarded))
                .append(Component.text(" forwarded)", Style.SECONDARY))
                .build());
        report.line(Component.text()
                .append(Component.text("    ", Style.PRIMARY))
                .append(Component.text("(use the # number in migrate/info)", Style.SECONDARY))
                .build());
        report.send(ctx.getSource());
        return total;
    }

    /**
     * One region row: local rows show their size, forwarded rows show the Compute Host they
     * live on. Both are clickable and open {@code /enigma distributed info #N}.
     */
    private static Component regionRow(
            final RegionNumbering.Entry entry,
            final @Nullable RemoteRegionExecutor executor
    ) {
        final boolean installed = executor != null && executor.isInstalledLocally(entry.regionId());
        final var row = Component.text()
                .append(Component.text("    ", Style.PRIMARY))
                .append(Component.text("#" + entry.number() + " (id=" + entry.regionId() + ")",
                        Style.INFORMATION, TextDecoration.BOLD));
        if (entry.forwarded()) {
            row.append(Component.text(" → Compute Host ", Style.PRIMARY))
                    .append(Style.value(String.valueOf(entry.forwardedToHost())))
                    .append(Component.text(" · ", Style.SECONDARY))
                    .append(Style.value(entry.chunks()))
                    .append(Component.text(" chunks", Style.SECONDARY))
                    .append(Component.text(" · ", Style.SECONDARY))
                    .append(Component.text("live", Style.good()));
        } else {
            row.append(Component.text(" ", Style.SECONDARY))
                    .append(Style.value(entry.chunks()))
                    .append(Component.text(" chunks, ", Style.SECONDARY))
                    .append(Style.value(entry.sections()))
                    .append(Component.text(" sections", Style.SECONDARY));
            if (installed) {
                row.append(Component.text(" · ", Style.SECONDARY))
                        .append(Component.text("from World Host", Style.good()));
            }
        }
        return row
                .hoverEvent(HoverEvent.showText(Component.text("Click to show region details", Style.SECONDARY)))
                .clickEvent(ClickEvent.runCommand("/enigma distributed info " + entry.number()))
                .build();
    }

    /**
     * Shows the migrations that are in flight plus the ones finished in the last minute, with
     * their stage so a stuck migration is visible without digging through the log.
     */
    private int migrations(final CommandContext<CommandSourceStack> ctx) {
        final DistributedBootstrap bootstrap = DistributedBootstrap.getInstance();
        if (!bootstrap.isStarted()) {
            Style.fail(ctx.getSource(), "Distributed runtime is not started");
            return 0;
        }
        final RemoteRegionExecutor executor = bootstrap.getRegionExecutor();
        if (executor == null) {
            Style.fail(ctx.getSource(), "Region executor is not available");
            return 0;
        }

        final java.util.List<RemoteRegionExecutor.MigrationView> views = executor.listMigrations();
        final Style.Report report = Style.report("Migrations");
        if (views.isEmpty()) {
            report.bullet("Total", Component.text()
                    .append(Style.value(0L))
                    .append(Component.text(" (history window 60s)", Style.SECONDARY))
                    .build());
            report.send(ctx.getSource());
            return 0;
        }

        final java.util.List<RegionNumbering.Entry> entries =
                RegionNumbering.list(MinecraftServer.getServer());
        final long now = System.currentTimeMillis();
        int stuck = 0;
        for (final RemoteRegionExecutor.MigrationView view : views) {
            final boolean isStuck = view.failure() == null
                    && view.stage() != RemoteRegionExecutor.MigrationStage.VERIFIED
                    && now - view.startedAtMs() > 60_000L;
            if (isStuck) {
                stuck++;
            }
            final var line = Component.text()
                    .append(Component.text(" - ", Style.LIST, TextDecoration.BOLD))
                    .append(Component.text(RegionNumbering.label(entries, view.regionId()),
                            Style.INFORMATION, TextDecoration.BOLD))
                    .append(Component.text(" → Compute Host ", Style.PRIMARY))
                    .append(Style.value(String.valueOf(view.targetInstanceId())))
                    .append(Component.text(": ", Style.PRIMARY))
                    .append(Component.text(view.stage().name(), stageColour(view.stage())))
                    .append(Component.text(", age ", Style.PRIMARY))
                    .append(Component.text(((now - view.startedAtMs()) / 1000L) + "s", Style.INFORMATION));
            if (view.progressTotal() > 0) {
                line.append(Component.text(", chunks ", Style.PRIMARY))
                        .append(Component.text(view.progressSent() + "/" + view.progressTotal(), Style.INFORMATION));
            }
            if (view.failure() != null) {
                line.append(Component.text(", failure: ", Style.PRIMARY))
                        .append(Component.text(view.failure(), Style.bad()));
            }
            if (isStuck) {
                line.append(Component.text(" STUCK", Style.bad(), TextDecoration.BOLD));
            }
            report.line(line.build());
        }

        if (stuck > 0) {
            report.line(Component.text(stuck + " migration(s) have not finished within 60s, check the server log",
                    Style.bad()));
        }
        report.send(ctx.getSource());
        return views.size();
    }

    private int debugBlockRead(final CommandContext<CommandSourceStack> ctx) {
        final int x = IntegerArgumentType.getInteger(ctx, "x");
        final int y = IntegerArgumentType.getInteger(ctx, "y");
        final int z = IntegerArgumentType.getInteger(ctx, "z");
        final ServerLevel world = MinecraftServer.getServer().getLevel(ServerLevel.OVERWORLD);
        if (world == null) {
            Style.fail(ctx.getSource(), "Overworld is not loaded");
            return 0;
        }

        final BlockPos pos = new BlockPos(x, y, z);
        final StringBuilder report = new StringBuilder();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        boolean ran;
        try {
            ran = runOnChunkThread(world, x >> 4, z >> 4, () -> {
                try {
                    report.append("block@").append(pos.toShortString())
                            .append(" = ").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()))
                            .append(" fullChunk=").append(world.getChunkIfLoaded(x >> 4, z >> 4) != null);
                    final net.minecraft.world.level.block.entity.BlockEntity blockEntity = world.getBlockEntity(pos);
                    if (blockEntity != null) {
                        report.append(" blockEntity=")
                                .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()));
                        if (blockEntity instanceof net.minecraft.world.Container container) {
                            report.append(" items=[");
                            boolean first = true;
                            for (int i = 0; i < Math.min(container.getContainerSize(), 27); i++) {
                                final net.minecraft.world.item.ItemStack stack = container.getItem(i);
                                if (stack.isEmpty()) {
                                    continue;
                                }
                                if (!first) {
                                    report.append(", ");
                                }
                                first = false;
                                report.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()))
                                        .append("x").append(stack.getCount());
                            }
                            report.append("]");
                        }
                    }
                } catch (final Throwable t) {
                    failure.set(t);
                }
            }, 5);
        } catch (final Throwable t) {
            Style.fail(ctx.getSource(), "Region task failed: " + t);
            return 0;
        }
        if (!ran) {
            Style.fail(ctx.getSource(), "Timed out waiting for the region thread of chunk (" + (x >> 4) + ", " + (z >> 4) + ")");
            return 0;
        }
        if (failure.get() != null) {
            Style.fail(ctx.getSource(), "Failed to read block: " + failure.get());
            return 0;
        }
        Style.send(ctx.getSource(), Style.value(report.toString()));
        return 1;
    }

    private int debugBlockWrite(final CommandContext<CommandSourceStack> ctx) {
        final String blockArg = StringArgumentType.getString(ctx, "block");
        final net.minecraft.world.level.block.Block block =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(blockArg));
        if (block == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString().equals(blockArg)) {
            Style.fail(ctx.getSource(), "Unknown block: " + blockArg);
            return 0;
        }

        final int x = IntegerArgumentType.getInteger(ctx, "x");
        final int y = IntegerArgumentType.getInteger(ctx, "y");
        final int z = IntegerArgumentType.getInteger(ctx, "z");
        final ServerLevel world = MinecraftServer.getServer().getLevel(ServerLevel.OVERWORLD);
        if (world == null) {
            Style.fail(ctx.getSource(), "Overworld is not loaded");
            return 0;
        }

        final BlockPos pos = new BlockPos(x, y, z);
        final StringBuilder report = new StringBuilder();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        boolean ran;
        try {
            ran = runOnChunkThread(world, x >> 4, z >> 4, () -> {
                try {
                    final net.minecraft.world.level.block.state.BlockState old = world.getBlockState(pos);
                    final boolean set = world.setBlockAndUpdate(pos, block.defaultBlockState());
                    report.append("set=").append(set)
                            .append(" old=").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(old.getBlock()));
                } catch (final Throwable t) {
                    failure.set(t);
                }
            }, 5);
        } catch (final Throwable t) {
            Style.fail(ctx.getSource(), "Region task failed: " + t);
            return 0;
        }
        if (!ran) {
            Style.fail(ctx.getSource(), "Timed out waiting for the region thread of chunk (" + (x >> 4) + ", " + (z >> 4) + ")");
            return 0;
        }
        if (failure.get() != null) {
            Style.fail(ctx.getSource(), "Failed to set block: " + failure.get());
            return 0;
        }
        Style.send(ctx.getSource(), Style.value(report.toString()));
        return 1;
    }

    /**
     * Places a chest holding one diamond at the target position. The chest marks real state:
     * both the block and its inventory are part of the chunk the snapshot carries, so a migrating
     * region should carry the diamond along.
     */
    private int debugChestSet(final CommandContext<CommandSourceStack> ctx) {
        final int x = IntegerArgumentType.getInteger(ctx, "x");
        final int y = IntegerArgumentType.getInteger(ctx, "y");
        final int z = IntegerArgumentType.getInteger(ctx, "z");
        final ServerLevel world = MinecraftServer.getServer().getLevel(ServerLevel.OVERWORLD);
        if (world == null) {
            Style.fail(ctx.getSource(), "Overworld is not loaded");
            return 0;
        }

        final BlockPos pos = new BlockPos(x, y, z);
        final StringBuilder report = new StringBuilder();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        boolean ran;
        try {
            ran = runOnChunkThread(world, x >> 4, z >> 4, () -> {
                try {
                    final net.minecraft.world.level.block.state.BlockState old = world.getBlockState(pos);
                    world.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
                    if (world.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                        chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
                        chest.setChanged();
                        report.append("chest@").append(pos.toShortString()).append(" contains minecraft:diamond")
                                .append(" old=").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(old.getBlock()));
                    } else {
                        report.append("chest@").append(pos.toShortString()).append(" block entity not created");
                    }
                } catch (final Throwable t) {
                    failure.set(t);
                }
            }, 5);
        } catch (final Throwable t) {
            Style.fail(ctx.getSource(), "Region task failed: " + t);
            return 0;
        }
        if (!ran) {
            Style.fail(ctx.getSource(), "Timed out waiting for the region thread of chunk (" + (x >> 4) + ", " + (z >> 4) + ")");
            return 0;
        }
        if (failure.get() != null) {
            Style.fail(ctx.getSource(), "Failed to set chest: " + failure.get());
            return 0;
        }
        Style.send(ctx.getSource(), Style.value(report.toString()));
        return 1;
    }

    /**
     * Runs {@code task} on the tick thread that owns the chunk, where {@code getCurrentWorldData()}
     * is valid. Blocks until the task finished or {@code timeoutSeconds} elapsed; returns false on
     * timeout. Exceptions thrown while submitting or running the task are allowed to escape so the
     * caller can turn them into a failure response.
     */
    private static boolean runOnChunkThread(
            final ServerLevel world,
            final int chunkX,
            final int chunkZ,
            final Runnable task,
            final int timeoutSeconds
    ) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(world, chunkX, chunkZ, () -> {
                try {
                    task.run();
                } catch (final Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });
        } catch (final Throwable t) {
            failure.set(t);
            latch.countDown();
        }
        final boolean ran;
        try {
            ran = latch.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (failure.get() != null) {
            throw new IllegalStateException(failure.get());
        }
        return ran;
    }

    private static java.util.OptionalLong explicitTarget(final CommandContext<CommandSourceStack> ctx) {
        try {
            return java.util.OptionalLong.of(LongArgumentType.getLong(ctx, "targetInstanceId"));
        } catch (final IllegalArgumentException e) {
            // The two-argument form was not used.
            return java.util.OptionalLong.empty();
        }
    }

}
