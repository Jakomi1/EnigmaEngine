package io.canvasmc.canvas.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.commands.CommandSourceStack;

/**
 * Shared visual style helpers for Enigma Engine chat commands.
 *
 * <p>All colours mirror {@code CanvasCommands} so every sub-command uses a
 * consistent palette.  The two inner classes ({@link Report} and
 * {@link Bullets}) are fluent builders that compose Adventure {@link Component}
 * trees and send them to a {@link CommandSourceStack} in a single call.
 */
public final class Style {

    // ---------------------------------------------------------------------------
    // Colour palette (match CanvasCommands constants)
    // ---------------------------------------------------------------------------

    /** Bright purple – titles and report headers. */
    public static final TextColor HEADER      = TextColor.color(118,  52, 212);
    /** Medium purple – key names and primary labels. */
    public static final TextColor PRIMARY     = TextColor.color(118,  52, 212);
    /** Soft purple – separators, units, low-emphasis text. */
    public static final TextColor SECONDARY   = TextColor.color(182, 118, 248);
    /** Lavender – values and numbers. */
    public static final TextColor INFORMATION = TextColor.color(208, 168, 252);
    /** Used for list bullet punctuation (e.g. " - "). */
    public static final TextColor LIST        = TextColor.color(140,  80, 220);
    /** Alias for SECONDARY – accent highlights. */
    public static final TextColor ACCENT      = SECONDARY;
    /** Very soft purple – muted / disabled text. */
    public static final TextColor MUTED       = TextColor.color(160, 130, 200);

    // ---------------------------------------------------------------------------
    // Semantic colour accessors
    // ---------------------------------------------------------------------------

    /** Bright green – good / enabled / success state. */
    public static TextColor good() {
        return TextColor.color(85, 255, 85);
    }

    /** Bright red – bad / error / disabled state. */
    public static TextColor bad() {
        return TextColor.color(255, 85, 85);
    }

    /** Yellow – warning / caution state. */
    public static TextColor warn() {
        return TextColor.color(255, 255, 85);
    }

    // ---------------------------------------------------------------------------
    // Formatted metric components
    // ---------------------------------------------------------------------------

    /**
     * Formats a TPS value.  Colour transitions from green (≥ 19) through yellow
     * (≥ 15) to red (below 15).
     */
    public static Component tps(final double tps) {
        final TextColor color;
        if (tps >= 19.0) {
            color = good();
        } else if (tps >= 15.0) {
            color = warn();
        } else {
            color = bad();
        }
        return Component.text(String.format("%.2f", tps), color);
    }

    /**
     * Formats a milliseconds-per-tick value.  Colour transitions from green
     * (≤ 40 ms) through yellow (≤ 60 ms) to red (above 60 ms).
     */
    public static Component mspt(final double mspt) {
        final TextColor color;
        if (mspt <= 40.0) {
            color = good();
        } else if (mspt <= 60.0) {
            color = warn();
        } else {
            color = bad();
        }
        return Component.text(String.format("%.2f", mspt) + " ms", color);
    }

    /**
     * Formats a CPU / tick utilisation ratio (0 – 1).  Colour transitions from
     * green (≤ 0.7) through yellow (≤ 0.9) to red (above 0.9).
     */
    public static Component util(final double ratio) {
        final TextColor color;
        if (ratio <= 0.70) {
            color = good();
        } else if (ratio <= 0.90) {
            color = warn();
        } else {
            color = bad();
        }
        return Component.text(String.format("%.1f%%", ratio * 100.0), color);
    }

    // ---------------------------------------------------------------------------
    // Generic value / on-off helpers
    // ---------------------------------------------------------------------------

    /**
     * Wraps any value in the {@link #INFORMATION} colour.  {@code toString()} is
     * called on the supplied object.
     */
    public static Component value(final Object v) {
        return Component.text(String.valueOf(v), INFORMATION);
    }

    /**
     * Returns a {@link #good() green} {@code "on"} component when {@code enabled}
     * is {@code true}, or a {@link #SECONDARY secondary-coloured} {@code "off"}
     * component otherwise.
     */
    public static Component onOff(final boolean enabled) {
        return enabled
            ? Component.text("on",  good())
            : Component.text("off", SECONDARY);
    }

    // ---------------------------------------------------------------------------
    // Direct send / fail helpers
    // ---------------------------------------------------------------------------

    /**
     * Sends a {@link Component} directly to {@code source}.  Uses the Adventure
     * API so the component colour and decoration are preserved in-game.
     */
    public static void send(final CommandSourceStack source, final Component message) {
        source.getBukkitSender().sendMessage(message);
    }

    /**
     * Sends a {@link #bad() red} error message to {@code source}.
     */
    public static void fail(final CommandSourceStack source, final String message) {
        source.getBukkitSender().sendMessage(Component.text(message, bad()));
    }

    // ---------------------------------------------------------------------------
    // Fluent builders
    // ---------------------------------------------------------------------------

    /**
     * Returns a new {@link Bullets} builder – a compact list of {@code key: value}
     * lines sent as a single block.
     */
    public static Bullets bullets() {
        return new Bullets();
    }

    /**
     * Returns a new {@link Report} builder with the given string title.
     */
    public static Report report(final String title) {
        return new Report(Component.text(title, HEADER, TextDecoration.BOLD));
    }

    /**
     * Returns a new {@link Report} builder with the given {@link Component} title.
     * Use this overload when the caller wants to supply a pre-styled title.
     */
    public static Report report(final Component title) {
        return new Report(title);
    }

    // ---------------------------------------------------------------------------
    // Inner class: Report
    // ---------------------------------------------------------------------------

    /**
     * Fluent builder for multi-section command output.  Supports headers, bullet
     * points, blank separator lines, and plain component lines.
     *
     * <p>Call {@link #send(CommandSourceStack)} when the report is complete.</p>
     */
    public static final class Report {

        private final java.util.List<Component> lines = new java.util.ArrayList<>();

        private Report(final Component title) {
            this.lines.add(title);
        }

        // ---- bullet overloads ------------------------------------------------

        /** Adds a {@code key: value} bullet where {@code value} is a plain string. */
        public Report bullet(final String key, final String value) {
            return bullet(key, Component.text(value, INFORMATION));
        }

        /** Adds a {@code key: value} bullet where {@code value} is a number or other object. */
        public Report bullet(final String key, final Object value) {
            return bullet(key, Component.text(String.valueOf(value), INFORMATION));
        }

        /** Adds a {@code key: value} bullet where {@code value} is a pre-built component. */
        public Report bullet(final String key, final Component value) {
            this.lines.add(Component.text()
                .append(Component.text(" - ", LIST, TextDecoration.BOLD))
                .append(Component.text(key + ": ", PRIMARY))
                .append(value)
                .build());
            return this;
        }

        /** Adds a {@code key: value} bullet with an explicit colour for the value text. */
        public Report bullet(final String key, final String value, final TextColor valueColor) {
            return bullet(key, Component.text(value, valueColor));
        }

        // ---- structural helpers ----------------------------------------------

        /** Adds a blank separator line. */
        public Report gap() {
            this.lines.add(Component.empty());
            return this;
        }

        /** Adds a bold sub-header line in the {@link Style#SECONDARY} colour. */
        public Report subHeader(final String text) {
            this.lines.add(Component.text(text, SECONDARY, TextDecoration.BOLD));
            return this;
        }

        /** Appends a pre-built {@link Component} line verbatim. */
        public Report line(final Component component) {
            this.lines.add(component);
            return this;
        }

        /** Sends every accumulated line to {@code source}. */
        public void send(final CommandSourceStack source) {
            final org.bukkit.command.CommandSender sender = source.getBukkitSender();
            for (final Component line : this.lines) {
                sender.sendMessage(line);
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Inner class: Bullets
    // ---------------------------------------------------------------------------

    /**
     * Fluent builder for a compact list of {@code key: value} bullets.  Unlike
     * {@link Report} this builder has no header; it is intended for single-topic
     * one-liner or short-list responses.
     */
    public static final class Bullets {

        private final java.util.List<Component> lines = new java.util.ArrayList<>();

        private Bullets() {
        }

        // ---- bullet overloads ------------------------------------------------

        /** Adds a {@code key: value} bullet where {@code value} is a plain string. */
        public Bullets bullet(final String key, final String value) {
            return bullet(key, Component.text(value, INFORMATION));
        }

        /** Adds a {@code key: value} bullet where {@code value} is a number or other object. */
        public Bullets bullet(final String key, final Object value) {
            return bullet(key, Component.text(String.valueOf(value), INFORMATION));
        }

        /** Adds a {@code key: value} bullet where {@code value} is a pre-built component. */
        public Bullets bullet(final String key, final Component value) {
            this.lines.add(Component.text()
                .append(Component.text(" - ", LIST, TextDecoration.BOLD))
                .append(Component.text(key + ": ", PRIMARY))
                .append(value)
                .build());
            return this;
        }

        /** Adds a {@code key: value} bullet with an explicit colour for the value text. */
        public Bullets bullet(final String key, final String value, final TextColor valueColor) {
            return bullet(key, Component.text(value, valueColor));
        }

        /** Appends a pre-built {@link Component} line verbatim. */
        public Bullets line(final Component component) {
            this.lines.add(component);
            return this;
        }

        /** Sends every accumulated line to {@code source}. */
        public void send(final CommandSourceStack source) {
            final org.bukkit.command.CommandSender sender = source.getBukkitSender();
            for (final Component line : this.lines) {
                sender.sendMessage(line);
            }
        }
    }

    // ---------------------------------------------------------------------------

    private Style() {
        throw new UnsupportedOperationException("Style is a utility class");
    }
}
