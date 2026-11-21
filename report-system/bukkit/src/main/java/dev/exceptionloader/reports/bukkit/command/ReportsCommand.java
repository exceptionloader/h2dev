package dev.exceptionloader.reports.bukkit.command;

import dev.exceptionloader.reports.bukkit.flow.ModerationFlow;
import dev.exceptionloader.reports.bukkit.ui.BrowserState;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /reports}: opens the report browser, or prints the open reports on the console.
 */
@Singleton
public class ReportsCommand implements TabExecutor {

    private final ModerationFlow moderation;

    @Inject
    public ReportsCommand(ModerationFlow moderation) {
        this.moderation = moderation;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (sender instanceof Player player) {
            moderation.browse(player, BrowserState.initial());
        } else {
            moderation.listOpen(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        return List.of();
    }
}
