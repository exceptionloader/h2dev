package dev.exceptionloader.reports.bukkit.command;

import dev.exceptionloader.reports.bukkit.Permissions;
import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.flow.ReportFlow;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /report [player]} for everyone, plus the moderation subcommands for staff.
 *
 * <p>Subcommands take precedence over player names, but only for senders that may use them. A
 * regular player can therefore still report somebody called "stats".
 */
@Singleton
public class ReportCommand implements TabExecutor {

    private final ReportFlow flow;
    private final Messages messages;
    private final Map<String, Subcommand> subcommands = new LinkedHashMap<>();

    @Inject
    public ReportCommand(
            ReportFlow flow,
            Messages messages,
            ResolveSubcommand resolve,
            RejectSubcommand reject,
            DetailsSubcommand details,
            StatsSubcommand stats,
            ReloadSubcommand reload) {
        this.flow = flow;
        this.messages = messages;
        for (Subcommand subcommand : List.of(resolve, reject, details, stats, reload)) {
            subcommands.put(subcommand.name(), subcommand);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length > 0) {
            Subcommand subcommand = subcommands.get(args[0].toLowerCase(Locale.ROOT));
            if (subcommand != null && sender.hasPermission(subcommand.permission())) {
                subcommand.execute(sender, Arrays.copyOfRange(args, 1, args.length));
                return true;
            }
        }

        if (!(sender instanceof Player player)) {
            sendUsage(sender);
            return true;
        }
        switch (args.length) {
            case 0 -> flow.selectTarget(player);
            case 1 -> flow.selectTarget(player, args[0]);
            default -> sendUsage(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>(TabCompletions.visibleTo(sender, args[0]));
            String prefix = args[0].toLowerCase(Locale.ROOT);
            subcommands.values().stream()
                    .filter(subcommand -> sender.hasPermission(subcommand.permission()))
                    .map(Subcommand::name)
                    .filter(name -> name.startsWith(prefix))
                    .forEach(suggestions::add);
            return suggestions;
        }
        Subcommand subcommand = subcommands.get(args[0].toLowerCase(Locale.ROOT));
        if (subcommand == null || !sender.hasPermission(subcommand.permission())) {
            return List.of();
        }
        return subcommand.complete(sender, Arrays.copyOfRange(args, 1, args.length));
    }

    private void sendUsage(CommandSender sender) {
        messages.send(sender, sender.hasPermission(Permissions.ADMIN) ? "report.admin-usage" : "report.usage");
    }
}
