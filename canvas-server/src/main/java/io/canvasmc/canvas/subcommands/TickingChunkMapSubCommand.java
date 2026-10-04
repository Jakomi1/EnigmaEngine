package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
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
                player.sendSystemMessage(Component.literal("Ticking chunk map stopped after an internal error: " + thrown)
                    .withStyle(ChatFormatting.RED));
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

        player.sendSystemMessage(Component.empty()
            .append(Component.literal("Ticking chunk map ").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
            .append(Component.literal("enabled").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" (radius " + settings.radius() + " chunks, 1s update).")
                .withStyle(ChatFormatting.GRAY))
        );
        sendLegend(player, settings);
        return Command.SINGLE_SUCCESS;
    }

    private static int disable(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final ServerPlayer player = player(context);
        if (!ENABLED.remove(player.getUUID())) {
            player.sendSystemMessage(Component.literal("Ticking chunk map is not enabled.")
                .withStyle(ChatFormatting.RED));
            return Command.SINGLE_SUCCESS;
        }
        player.sendSystemMessage(Component.literal("Ticking chunk map disabled.")
            .withStyle(ChatFormatting.YELLOW));
        return Command.SINGLE_SUCCESS;
    }

    private static int radius(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final ServerPlayer player = player(context);
        final int radius = IntegerArgumentType.getInteger(context, "chunks");
        final Settings previous = SETTINGS.getOrDefault(player.getUUID(), new Settings(DEFAULT_RADIUS, true));
        SETTINGS.put(player.getUUID(), new Settings(radius, previous.labels()));
        player.sendSystemMessage(Component.literal("Ticking chunk map radius set to " + radius + " chunks.")
            .withStyle(ChatFormatting.AQUA));
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
        player.sendSystemMessage(Component.literal("Region labels " + (enabled ? "enabled" : "disabled") + ".")
            .withStyle(ChatFormatting.AQUA));
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
            player.sendSystemMessage(Component.literal("Ticking chunk map is not enabled.")
                .withStyle(ChatFormatting.RED));
            return Command.SINGLE_SUCCESS;
        }

        final Settings settings = SETTINGS.getOrDefault(player.getUUID(), new Settings(DEFAULT_RADIUS, true));
        final Snapshot snapshot = collect(player.level(), player.chunkPosition(), settings.radius());

        line(player, "ticking chunks:   ", String.valueOf(snapshot.counts.ticking), ChatFormatting.GREEN);
        line(player, "ready (unticked): ", String.valueOf(snapshot.counts.ready), ChatFormatting.YELLOW);
        line(player, "transient:        ", String.valueOf(snapshot.counts.transientChunks), ChatFormatting.GOLD);
        line(player, "dying:            ", String.valueOf(snapshot.counts.dead), ChatFormatting.RED);
        line(player, "not loaded:       ", String.valueOf(snapshot.counts.unloaded), ChatFormatting.DARK_GRAY);
        line(player, "ticking regions:  ", String.valueOf(snapshot.counts.tickingRegions), ChatFormatting.AQUA);
        line(player, "colours in use:   ", snapshot.visibleRegionIds.size() + "/" + REGION_COLOURS.length, ChatFormatting.WHITE);
        return Command.SINGLE_SUCCESS;
    }

    private static ServerPlayer player(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return context.getSource().getPlayerOrException();
    }

    private static void line(final ServerPlayer player, final String label, final String value, final ChatFormatting style) {
        player.sendSystemMessage(Component.empty()
            .append(Component.literal("  " + label).withStyle(ChatFormatting.GRAY))
            .append(Component.literal(value).withStyle(style))
        );
    }

    private static void sendLegend(final ServerPlayer player, final Settings settings) {
        player.sendSystemMessage(Component.empty()
            .append(Component.literal("--- Ticking chunk map ---").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD))
        );

        final Snapshot snapshot = collect(player.level(), player.chunkPosition(), settings.radius());
        if (snapshot.visibleRegionIds.isEmpty()) {
            player.sendSystemMessage(Component.empty()
                .append(Component.literal("no ticking region covers this area right now").withStyle(ChatFormatting.GRAY))
            );
        } else {
            player.sendSystemMessage(Component.literal("ticking regions near you:").withStyle(ChatFormatting.GRAY));
            for (final long regionId : snapshot.visibleRegionIds) {
                final ChatFormatting colour = colourFor(regionId);
                player.sendSystemMessage(Component.empty()
                    .append(Component.literal("  ■ ").withStyle(colour, ChatFormatting.BOLD))
                    .append(Component.literal(regionId + " ").withStyle(colour))
                    .append(Component.literal("(" + (REGION_COLOURS.length - freeSlots()) + " colours available)")
                        .withStyle(ChatFormatting.DARK_GRAY))
                );
            }
        }

        player.sendSystemMessage(Component.empty()
            .append(Component.literal("■").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" ticking, in the region's own colour").withStyle(ChatFormatting.GRAY))
        );
        player.sendSystemMessage(Component.empty()
            .append(Component.literal("▒").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" loaded but unticked: kept in memory, pulled out of the ticking region to keep it small")
                .withStyle(ChatFormatting.GRAY))
        );
        player.sendSystemMessage(Component.empty()
            .append(Component.literal("░").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(" transient: region is being created, split or merged").withStyle(ChatFormatting.GRAY))
        );
        player.sendSystemMessage(Component.empty()
            .append(Component.literal("x").withStyle(ChatFormatting.RED))
            .append(Component.literal(" dying: region is scheduled for removal").withStyle(ChatFormatting.GRAY))
        );
        player.sendSystemMessage(Component.empty()
            .append(Component.literal("·").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(" not loaded: no regionizer region owns this chunk").withStyle(ChatFormatting.GRAY))
        );
        player.sendSystemMessage(Component.empty()
            .append(Component.literal("N").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
            .append(Component.literal(" you, ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(settings.labels()
                ? "region ids shown in the header"
                : "region ids hidden (use /enigma chunks labels on)").withStyle(ChatFormatting.GRAY))
        );
    }

    private static void render(final ServerPlayer player, final Settings settings, final Snapshot snapshot) {
        final ChunkPos center = player.chunkPosition();
        final int radius = settings.radius();

        final StringBuilder header = new StringBuilder();
        header.append("§8Chunks §7@ §f").append(center.x()).append("§8, §f").append(center.z());
        header.append(" §8| §7ticking §f").append(snapshot.counts.ticking);
        header.append(" §8| §7unticked §f").append(snapshot.counts.ready);
        header.append(" §8| §7regions §f").append(snapshot.counts.tickingRegions);
        if (settings.labels() && !snapshot.visibleRegionIds.isEmpty()) {
            header.append(" §8| §7");
            final int shown = Math.min(MAX_HEADER_REGIONS, snapshot.visibleRegionIds.size());
            for (int i = 0; i < shown; i++) {
                final long regionId = snapshot.visibleRegionIds.get(i);
                header.append("§").append(colourCode(colourFor(regionId))).append(regionId).append("§7 ");
            }
            if (snapshot.visibleRegionIds.size() > shown) {
                header.append("§8+").append(snapshot.visibleRegionIds.size() - shown).append("§7 ");
            }
        }
        player.sendSystemMessage(Component.literal(header.toString()));

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
            player.sendSystemMessage(Component.literal(row.toString()));
        }
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
