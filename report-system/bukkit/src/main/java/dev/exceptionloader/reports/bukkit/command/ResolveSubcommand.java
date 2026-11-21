package dev.exceptionloader.reports.bukkit.command;

import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.flow.ModerationFlow;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bukkit.command.CommandSender;

@Singleton
class ResolveSubcommand implements Subcommand {

    private final ModerationFlow moderation;
    private final Messages messages;

    @Inject
    ResolveSubcommand(ModerationFlow moderation, Messages messages) {
        this.moderation = moderation;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "resolve";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length != 1) {
            messages.send(sender, "moderation.resolve-usage");
            return;
        }
        moderation.resolve(sender, args[0], () -> {
        });
    }
}
