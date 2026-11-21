package dev.exceptionloader.reports.bukkit.command;

import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class TabCompletions {

    private TabCompletions() {
    }

    /**
     * Names of online players starting with {@code prefix}, without vanished players.
     */
    static List<String> visibleTo(CommandSender sender, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .filter(player -> !(sender instanceof Player viewer) || (viewer.canSee(player) && !viewer.equals(player)))
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
