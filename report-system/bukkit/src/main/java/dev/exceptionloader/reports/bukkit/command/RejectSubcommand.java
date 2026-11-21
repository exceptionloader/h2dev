package dev.exceptionloader.reports.bukkit.command;

import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.flow.ModerationFlow;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Arrays;
import org.bukkit.command.CommandSender;

@Singleton
class RejectSubcommand implements Subcommand {

    private final ModerationFlow moderation;
    private final Messages messages;

    @Inject
    RejectSubcommand(ModerationFlow moderation, Messages messages) {
        this.moderation = moderation;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "reject";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "moderation.reject-usage");
            return;
        }
        String note = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        moderation.reject(sender, args[0], note, () -> {
        });
    }
}
