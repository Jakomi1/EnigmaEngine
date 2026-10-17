package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.canvasmc.canvas.commands.SubCommand;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;

/**
 * Top level {@code /enigma info}, so the server overview is reachable without knowing that
 * it lives under {@code /enigma regionizer info}.
 */
public class InfoSubCommand implements SubCommand {

    @Override
    public String getDescription() {
        return "Shows an overview of Enigma: TPS, MSPT, load scaling, memory and region stats";
    }

    @Override
    public String getName() {
        return "info";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(final LiteralArgumentBuilder<CommandSourceStack> base, final CommandBuildContext buildContext) {
        return base
            .requires(this.check("info"))
            .executes(RegionizerSubCommand::info);
    }
}
