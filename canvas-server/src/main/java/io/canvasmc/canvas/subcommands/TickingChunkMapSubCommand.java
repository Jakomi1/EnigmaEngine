package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.canvasmc.canvas.commands.Style;
import io.canvasmc.canvas.commands.SubCommand;
import io.canvasmc.canvas.region.WorldRegionizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Live overlay of which chunks the Enigma regionizer is actually ticking.
 * <p>
 * Every ticking region keeps its own stable colour for as long as that region exists, so a
 * region can be followed across frames, merges and splits. Chunks that are still owned by a
 * regionizer region but are not on a tick thread right now are shown separately per lifecycle
 * state, because a {@code READY} chunk is exactly one that Enigma kept loaded while pulling
 * it out of a ticking region to keep that region small.
 * </p>
 * <p>
 * The update is driven from the regular region tick (see the {@code Canvas - ticking chunk
 * map} hook in {@code MinecraftServer}), so it needs no plugin and always runs on a region
 * thread.
 * </p>
 */
public class TickingChunkMapSubCommand implements SubCommand {

    private static final int DEFAULT_RADIUS = 16;
    private static final int MAX_RADIUS = 64;
    private static final long UPDATE_INTERVAL_NANOS = 1_000_000_000L;
    private static final int MAX_HEADER_REGIONS = 6;

    /**
     * Colours handed out to ticking regions. All distinct so two neighbouring regions never
     * share a colour, and deliberately excluding {@link ChatFormatting#RED} and
     * {@link ChatFormatting#DARK_RED}, which are reserved for the dying/unloading state.
     */
    private static final ChatFormatting[] REGION_COLOURS = {
        ChatFormatting.DARK_BLUE,
        ChatFormatting.DARK_GREEN,
        ChatFormatting.DARK_AQUA,
        ChatFormatting.DARK_PURPLE,
        ChatFormatting.GOLD,
        ChatFormatting.GRAY,
        ChatFormatting.BLUE,
        ChatFormatting.GREEN,
        ChatFormatting.AQUA,
        ChatFormatting.LIGHT_PURPLE,
        ChatFormatting.YELLOW,
        ChatFormatting.WHITE
    };

    private static final Map<UUID, Settings> SETTINGS = new ConcurrentHashMap<>();
    private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();

    /**
     * Stable region id to colour slot assignment, plus the reverse mapping so a slot can be
     * released again once its region is gone.
     */
    private static final Map<Long, Integer> REGION_SLOTS = new HashMap<>();
    private static final Map<Integer, Long> SLOT_REGIONS = new HashMap<>();
    private static final Object COLOUR_LOCK = new Object();

    private static final AtomicLong LAST_UPDATE = new AtomicLong();

    private record Settings(int radius, boolean labels) {
    }

    @Override
    public String getDescription() {
        return "Live overlay of the chunks Enigma is actually ticking, each ticking region in its own colour";
    }

    @Override
    public String getName() {
        return "chunks";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(final LiteralArgumentBuilder<CommandSourceStack> base, final CommandBuildContext buildContext) {
        return base
            .executes(TickingChunkMapSubCommand::toggle)
            .then(literal("on")
                .requires(this.check("on"))
                .executes(TickingChunkMapSubCommand::enable))
            .then(literal("off")
                .requires(this.check("off"))
                .executes(TickingChunkMapSubCommand::disable))
            .then(literal("radius")
                .requires(this.check("radius"))
                .then(argument("chunks", IntegerArgumentType.integer(1, MAX_RADIUS))
                    .executes(TickingChunkMapSubCommand::radius)))
            .then(literal("labels")
                .requires(this.check("labels"))
                .then(argument("enabled", StringArgumentType.word())
                    .suggests((_, builder) -> {
                        builder.suggest("on");
                        builder.suggest("off");
                        return builder.buildFuture();
                    })
                    .executes(TickingChunkMapSubCommand::labels)))
            .then(literal("legend")
                .requires(this.check("legend"))
                .executes(TickingChunkMapSubCommand::legend))
            .then(literal("stats")
                .requires(this.check("stats"))
                .executes(TickingChunkMapSubCommand::stats));
    }

    /**
     * Region tick hook, invoked once per region tick by the server. Renders at most once per
     * second globally, no matter how many regions are ticking.
     *
     * @param level the level of the region that is currently ticking
     */
    public static void tickRegion(final ServerLevel level) {
        if (ENABLED.isEmpty()) {
            return;
        }

        final long now = System.nanoTime();
        final long last = LAST_UPDATE.get();
        if (now - last < UPDATE_INTERVAL_NANOS || !LAST_UPDATE.compareAndSet(last, now)) {
            return;
        }

        final Map<UUID, ServerPlayer> online = new ConcurrentHashMap<>();
        for (final ServerLevel candidate : level.getServer().getAllLevels()) {
            for (final ServerPlayer player : candidate.players()) {
                online.put(player.getUUID(), player);
            }
        }

        for (final UUID uuid : List.copyOf(ENABLED)) {
            final ServerPlayer player = online.get(uuid);
            if (player == null || player.isRemoved()) {
                ENABLED.remove(uuid);
                SETTINGS.remove(uuid);
                continue;
            }

            final Settings settings = SETTINGS.getOrDefault(uuid, new Settings(DEFAULT_RADIUS, true));
            try {
                final Snapshot snapshot = collect(player.level(), player.chunkPosition(), settings.radius());
                render(player, settings, snapshot);
            } catch (final Throwable thrown) {
                ENABLED.remove(uuid);
                send(player, Component.text("Ticking chunk map stopped after an internal error: " + thrown, Style.bad()));
            }
        }
    }

    private static int toggle(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (ENABLED.contains(player(context).getUUID())) {
            return disable(context);
        }
        return enable(context);
    }

    private static int enable(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final ServerPlayer player = player(context);
        final Settings settings = SETTINGS.getOrDefault(player.getUUID(), new Settings(DEFAULT_RADIUS, true));
        SETTINGS.put(player.getUUID(), settings);
        ENABLED.add(player.getUUID());

        send(player, Style.bullets()
            .bullet("Ticking chunk map",
                "enabled (radius " + settings.radius() + " chunks, 1s update)", Style.good())
            .build());
        sendLegend(player, settings);
        return Command.SINGLE_SUCCESS;
    }

    private static int disable(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final ServerPlayer player = player(context);
        if (!ENABLED.remove(player.getUUID())) {
            send(player, Component.text("Ticking chunk map is not enabled.", Style.bad()));
            return Command.SINGLE_SUCCESS;
        }
        send(player, Style.bullets().bullet("Ticking chunk map", "disabled", Style.warn()).build());
        return Command.SINGLE_SUCCESS;
    }

    private static int radius(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final ServerPlayer player = player(context);
        final int radius = IntegerArgumentType.getInteger(context, "chunks");
        final Settings previous = SETTINGS.getOrDefault(player.getUUID(), new Settings(DEFAULT_RADIUS, true));
        SETTINGS.put(player.getUUID(), new Settings(radius, previous.labels()));
        send(player, Style.bullets().bullet("Radius", radius + " chunks").build());
        return Command.SINGLE_SUCCESS;
    }

    private static int labels(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final ServerPlayer player = player(context);
        final boolean enabled = switch (StringArgumentType.getString(context, "enabled").toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> true;
            default -> false;
        };
        final Settings previous = SETTINGS.getOrDefault(player.getUUID(), new Settings(DEFAULT_RADIUS, true));
        SETTINGS.put(player.getUUID(), new Settings(previous.radius(), enabled));
        send(player, Style.bullets().bullet("Region labels", Style.onOff(enabled)).build());
        return Command.SINGLE_SUCCESS;
    }

    private static int legend(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final ServerPlayer player = player(context);
        final Settings settings = SETTINGS.getOrDefault(player.getUUID(), new Settings(DEFAULT_RADIUS, true));
        sendLegend(player, settings);
        return Command.SINGLE_SUCCESS;
    }

    private static int stats(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final ServerPlayer player = player(context);
        if (!ENABLED.contains(player.getUUID())) {
            send(player, Component.text("Ticking chunk map is not enabled.", Style.bad()));
            return Command.SINGLE_SUCCESS;
        }

        final Settings settings = SETTINGS.getOrDefault(player.getUUID(), new Settings(DEFAULT_RADIUS, true));
        final Snapshot snapshot = collect(player.level(), player.chunkPosition(), settings.radius());

        final Style.Report report = Style.report("Ticking chunk map stats");
        report.bullet("Ticking chunks", Component.text(snapshot.counts.ticking, Style.good()));
        report.bullet("Ready (unticked)", Component.text(snapshot.counts.ready, Style.warn()));
        report.bullet("Transient", Component.text(snapshot.counts.transientChunks, NamedTextColor.GOLD));
        report.bullet("Dying", Component.text(snapshot.counts.dead, Style.bad()));
        report.bullet("Not loaded", Component.text(snapshot.counts.unloaded, NamedTextColor.DARK_GRAY));
        report.bullet("Ticking regions", Component.text(snapshot.counts.tickingRegions, Style.SECONDARY));
        report.bullet("Colours in use", Component.text(
            snapshot.visibleRegionIds.size() + "/" + REGION_COLOURS.length, Style.INFORMATION));
        send(player, report.build());
        return Command.SINGLE_SUCCESS;
    }

    private static ServerPlayer player(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return context.getSource().getPlayerOrException();
    }

    /**
     * The commands in this class work off a {@link ServerPlayer} instead of a
     * {@code CommandSourceStack}, so the styled messages go through the bukkit entity.
     */
    private static void send(final ServerPlayer player, final Component message) {
        player.getBukkitEntity().sendMessage(message);
    }

    private static void sendLegend(final ServerPlayer player, final Settings settings) {
        final Style.Report report = Style.report("Ticking chunk map");

        final Snapshot snapshot = collect(player.level(), player.chunkPosition(), settings.radius());
        if (snapshot.visibleRegionIds.isEmpty()) {
            report.bullet("Ticking regions",
                Component.text("none covers this area right now", Style.SECONDARY));
        } else {
            report.subHeader("Ticking regions near you");
            for (final long regionId : snapshot.visibleRegionIds) {
                final TextColor colour = textColourFor(regionId);
                report.line(Component.text()
                    .append(Component.text("    ", Style.PRIMARY))
                    .append(Component.text("■ ", colour, TextDecoration.BOLD))
                    .append(Component.text(regionId + " ", colour))
                    .append(Component.text("(" + (REGION_COLOURS.length - freeSlots()) + " colours available)",
                        Style.SECONDARY))
                    .build());
            }
        }

        report.gap();
        report.subHeader("Legend");
        report.line(legendKey("■", NamedTextColor.WHITE, "ticking, in the region's own colour"));
        report.line(legendKey("▒", NamedTextColor.YELLOW, "loaded but unticked: kept in memory, pulled out of the ticking region to keep it small"));
        report.line(legendKey("░", NamedTextColor.GOLD, "transient: region is being created, split or merged"));
        report.line(legendKey("x", NamedTextColor.RED, "dying: region is scheduled for removal"));
        report.line(legendKey("·", NamedTextColor.DARK_GRAY, "not loaded: no regionizer region owns this chunk"));
        report.line(Component.text()
            .append(Component.text("    ", Style.PRIMARY))
            .append(Component.text("N", Style.INFORMATION, TextDecoration.BOLD))
            .append(Component.text(" you, ", Style.SECONDARY))
            .append(Component.text(settings.labels()
                ? "region ids shown in the header"
                : "region ids hidden (use /enigma chunks labels on)", Style.SECONDARY))
            .build());

        send(player, report.build());
    }

    private static Component legendKey(final String glyph, final TextColor colour, final String description) {
        return Component.text()
            .append(Component.text("    ", Style.PRIMARY))
            .append(Component.text(glyph + " ", colour, TextDecoration.BOLD))
            .append(Component.text(description, Style.SECONDARY))
            .build();
    }

    private static void render(final ServerPlayer player, final Settings settings, final Snapshot snapshot) {
        final ChunkPos center = player.chunkPosition();
        final int radius = settings.radius();

        final var header = Component.text()
            .append(Component.text("Chunks ", Style.HEADER, TextDecoration.BOLD))
            .append(Component.text("@ " + center.x() + ", " + center.z(), Style.INFORMATION))
            .append(Component.text(" | ", Style.LIST))
            .append(Component.text("ticking ", Style.PRIMARY))
            .append(Style.value(snapshot.counts.ticking))
            .append(Component.text(" | ", Style.LIST))
            .append(Component.text("unticked ", Style.PRIMARY))
            .append(Style.value(snapshot.counts.ready))
            .append(Component.text(" | ", Style.LIST))
            .append(Component.text("regions ", Style.PRIMARY))
            .append(Style.value(snapshot.counts.tickingRegions));
        if (settings.labels() && !snapshot.visibleRegionIds.isEmpty()) {
            header.append(Component.text(" | ", Style.LIST));
            final int shown = Math.min(MAX_HEADER_REGIONS, snapshot.visibleRegionIds.size());
            for (int i = 0; i < shown; i++) {
                final long regionId = snapshot.visibleRegionIds.get(i);
                header.append(Component.text(regionId + " ", textColourFor(regionId)));
            }
            if (snapshot.visibleRegionIds.size() > shown) {
                header.append(Component.text("+" + (snapshot.visibleRegionIds.size() - shown), Style.SECONDARY));
            }
        }
        send(player, header.build());

        for (int dz = -radius; dz <= radius; dz++) {
            final StringBuilder row = new StringBuilder();
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dz == 0) {
                    row.append("§fN");
                    continue;
                }

                final int gx = dx + radius;
                final int gz = dz + radius;
                final long regionId = snapshot.chunkRegion[gz][gx];
                row.append(switch (snapshot.chunkState[gz][gx]) {
                    case TICKING -> "§" + colourCode(colourFor(regionId)) + "■";
                    case READY -> "§e▒";
                    case TRANSIENT -> "§6░";
                    case DEAD -> "§cx";
                    case UNLOADED -> "§8·";
                });
            }
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(row.toString()));
        }
    }

    /**
     * The same region colour as {@link #colourFor(long)}, but as an adventure colour so the
     * styled header and legend can use it. This ChatFormatting copy only carries the legacy
     * code, so the classic {@code §} palette is mapped by hand.
     */
    private static TextColor textColourFor(final long regionId) {
        return rgbFor(colourFor(regionId).code);
    }

    private static TextColor rgbFor(final char legacyCode) {
        return switch (legacyCode) {
            case '0' -> TextColor.color(0x000000);
            case '1' -> TextColor.color(0x0000AA);
            case '2' -> TextColor.color(0x00AA00);
            case '3' -> TextColor.color(0x00AAAA);
            case '4' -> TextColor.color(0xAA0000);
            case '5' -> TextColor.color(0xAA00AA);
            case '6' -> TextColor.color(0xFFAA00);
            case '7' -> TextColor.color(0xAAAAAA);
            case '8' -> TextColor.color(0x555555);
            case '9' -> TextColor.color(0x5555FF);
            case 'a' -> TextColor.color(0x55FF55);
            case 'b' -> TextColor.color(0x55FFFF);
            case 'c' -> TextColor.color(0xFF5555);
            case 'd' -> TextColor.color(0xFF55FF);
            case 'e' -> TextColor.color(0xFFFF55);
            default -> TextColor.color(0xFFFFFF);
        };
    }

    private static String colourCode(final ChatFormatting formatting) {
        return Integer.toHexString(formatting.code & 0xF);
    }

    private static int freeSlots() {
        synchronized (COLOUR_LOCK) {
            return REGION_COLOURS.length - SLOT_REGIONS.size();
        }
    }

    private static ChatFormatting colourFor(final long regionId) {
        final Integer slot = REGION_SLOTS.get(regionId);
        if (slot == null) {
            return ChatFormatting.WHITE;
        }
        return REGION_COLOURS[slot % REGION_COLOURS.length];
    }

    /**
     * Hands out a colour slot for a ticking region. A region keeps its slot for its whole
     * lifetime, so its colour never changes between frames.
     */
    private static void retainRegion(final long regionId) {
        if (REGION_SLOTS.containsKey(regionId)) {
            return;
        }
        synchronized (COLOUR_LOCK) {
            if (REGION_SLOTS.containsKey(regionId)) {
                return;
            }
            for (int slot = 0; slot < REGION_COLOURS.length; slot++) {
                if (!SLOT_REGIONS.containsKey(slot)) {
                    REGION_SLOTS.put(regionId, slot);
                    SLOT_REGIONS.put(slot, regionId);
                    return;
                }
            }
            // more live regions than colours: share deterministically, the legend makes it clear
            REGION_SLOTS.put(regionId, Math.floorMod((int) regionId, REGION_COLOURS.length));
        }
    }

    /**
     * Frees the colour slots of regions that no longer exist, so fresh regions get their own
     * colour again instead of permanently sharing one.
     */
    private static void releaseDeadRegions(final Set<Long> liveTickingRegions) {
        synchronized (COLOUR_LOCK) {
            final List<Long> gone = REGION_SLOTS.keySet().stream()
                .filter(id -> !liveTickingRegions.contains(id))
                .toList();
            for (final long regionId : gone) {
                final Integer slot = REGION_SLOTS.remove(regionId);
                if (slot != null) {
                    SLOT_REGIONS.remove(slot);
                }
            }
        }
    }

    private enum CellState {
        TICKING,
        READY,
        TRANSIENT,
        DEAD,
        UNLOADED
    }

    private static final class Counts {
        private int ticking;
        private int ready;
        private int transientChunks;
        private int dead;
        private int unloaded;
        private int tickingRegions;
        private int otherRegions;
    }

    private record Snapshot(
        long[][] chunkRegion,
        CellState[][] chunkState,
        List<Long> visibleRegionIds,
        Counts counts
    ) {
        private static final long NO_REGION = -1L;
    }

    private static Snapshot collect(final ServerLevel level, final ChunkPos center, final int radius) {
        final int size = radius * 2 + 1;
        final long[][] chunkRegion = new long[size][size];
        final CellState[][] chunkState = new CellState[size][size];
        final Counts counts = new Counts();

        for (int z = 0; z < size; z++) {
            Arrays.fill(chunkRegion[z], Snapshot.NO_REGION);
            Arrays.fill(chunkState[z], CellState.UNLOADED);
        }

        final int minX = center.x() - radius;
        final int minZ = center.z() - radius;

        final Set<Long> visibleTicking = new HashSet<>();
        final Set<Long> liveTicking = new HashSet<>();

        level.regioniser.computeForAllChunkRegions(region -> {
            final WorldRegionizer.ChunkRegion.State state = region.getState();
            final long regionId = region.getId();

            if (state == WorldRegionizer.ChunkRegion.State.TICKING) {
                counts.tickingRegions++;
                liveTicking.add(regionId);
                retainRegion(regionId);
            } else {
                counts.otherRegions++;
            }

            for (final long packed : region.getOwnedPackedChunkPositions()) {
                final int chunkX = (int) packed;
                final int chunkZ = (int) (packed >> 32);
                final int gx = chunkX - minX;
                final int gz = chunkZ - minZ;
                if (gx < 0 || gx >= size || gz < 0 || gz >= size) {
                    continue;
                }

                switch (state) {
                    case TICKING -> {
                        // a chunk is owned by exactly one region, so this always wins
                        chunkRegion[gz][gx] = regionId;
                        chunkState[gz][gx] = CellState.TICKING;
                        counts.ticking++;
                        visibleTicking.add(regionId);
                    }
                    case READY -> {
                        if (chunkState[gz][gx] == CellState.UNLOADED) {
                            chunkState[gz][gx] = CellState.READY;
                            counts.ready++;
                        }
                    }
                    case TRANSIENT -> {
                        if (chunkState[gz][gx] == CellState.UNLOADED) {
                            chunkState[gz][gx] = CellState.TRANSIENT;
                            counts.transientChunks++;
                        }
                    }
                    case DEAD -> {
                        if (chunkState[gz][gx] == CellState.UNLOADED) {
                            chunkState[gz][gx] = CellState.DEAD;
                            counts.dead++;
                        }
                    }
                }
            }
        });

        releaseDeadRegions(liveTicking);

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                if (chunkState[z][x] == CellState.UNLOADED) {
                    counts.unloaded++;
                }
            }
        }

        final List<Long> visibleRegionIds = new ArrayList<>(visibleTicking);
        visibleRegionIds.sort(Comparator.<Long>comparingInt(id -> {
            final Integer slot = REGION_SLOTS.get(id);
            return slot != null ? slot : REGION_COLOURS.length;
        }).thenComparing(Comparator.naturalOrder()));

        return new Snapshot(chunkRegion, chunkState, visibleRegionIds, counts);
    }
}
