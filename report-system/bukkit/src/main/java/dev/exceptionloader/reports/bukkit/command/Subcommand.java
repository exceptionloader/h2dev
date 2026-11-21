package dev.exceptionloader.reports.bukkit.command;

import dev.exceptionloader.reports.bukkit.Permissions;
import java.util.List;
import org.bukkit.command.CommandSender;

/**
 * An argument of {@code /report} that is not a player name.
 */
interface Subcommand {

    String name();

    void execute(CommandSender sender, String[] args);

    default String permission() {
        return Permissions.ADMIN;
    }

    default List<String> complete(CommandSender sender, String[] args) {
        return List.of();
    }
}
