package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.canvasmc.canvas.GlobalConfiguration;
import io.canvasmc.canvas.WorldConfig;
import io.canvasmc.canvas.commands.Style;
import io.canvasmc.canvas.commands.SubCommand;
import io.canvasmc.canvas.util.Util;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;

public class ReloadSubCommand implements SubCommand {

    @Override
    public String getDescription() {
        return "Reloads the EnigmaEngine configuration";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(final LiteralArgumentBuilder<CommandSourceStack> base, final CommandBuildContext buildContext) {
        return base.executes(context -> {
            final CommandSourceStack source = context.getSource();
            final long start = System.nanoTime();

            // warn the source, as technically this can cause issues and should
            // only be used for development purposes
            Style.fail(source, "Some configuration options cannot be changed at runtime or may work incorrectly after reloading.");
            Style.fail(source, "This command is unsupported. If you encounter issues, please run /stop");

            // reload global and world configs
            GlobalConfiguration.reload();
            WorldConfig.reload();

            final String took = Util.formatNanosToLargestWholeUnit(System.nanoTime() - start);
            Style.bullets().bullet("Configs", "reloaded in " + took, Style.good()).send(source);
            GlobalConfiguration.broadcast(
                "Reloaded all EnigmaEngine solid and patch configurations in " + took,
                GlobalConfiguration.INFO
            );
            return Command.SINGLE_SUCCESS;
        });
    }

    @Override
    public boolean isAllowedSelfCommand() {
        return false;
    }

    @Override
    public boolean hasExtraArgs() {
        return false;
    }

    @Override
    public String getName() {
        return "reload";
    }
}
