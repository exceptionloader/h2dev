package dev.exceptionloader.reports.bukkit.command;

import dev.exceptionloader.reports.bukkit.flow.ModerationFlow;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import org.bukkit.command.CommandSender;

@Singleton
class StatsSubcommand implements Subcommand {

    private final ModerationFlow moderation;

    @Inject
    StatsSubcommand(ModerationFlow moderation) {
        this.moderation = moderation;
    }

    @Override
    public String name() {
        return "stats";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            moderation.showStatistics(sender);
        } else {
            moderation.showStatistics(sender, args[0]);
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? TabCompletions.visibleTo(sender, args[0]) : List.of();
    }
}
