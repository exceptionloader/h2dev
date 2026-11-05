package de.minesmp.core.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Basis fuer alle Befehle. Wird dynamisch in der CommandMap registriert. */
public abstract class BaseCommand extends Command {
    protected BaseCommand(String name, String permission, String description, String... aliases) {
        super(name, description, "/" + name, Arrays.asList(aliases));
        if (permission != null) setPermission(permission);
    }

    protected abstract void run(CommandSender sender, String label, String[] args);

    protected List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) return null; // Spielernamen
        return Collections.emptyList();
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if (getPermission() != null && !sender.hasPermission(getPermission())) {
            Msg.send(sender, "no-permission", "{prefix}&cDazu hast du keine Berechtigung.");
            return true;
        }
        try {
            run(sender, label, args);
        } catch (Exception ex) {
            Msg.send(sender, "command-error", "{prefix}&cBeim Ausfuehren ist ein Fehler aufgetreten.");
            Bukkit.getLogger().severe("[MineSMP] Fehler in /" + label + ": " + ex);
            ex.printStackTrace();
        }
        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        if (getPermission() != null && !sender.hasPermission(getPermission())) return Collections.emptyList();
        List<String> res = complete(sender, args);
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (res == null) {
            res = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (sender instanceof Player sp && !sp.canSee(p)) continue;
                res.add(p.getName());
            }
        }
        List<String> out = new ArrayList<>();
        for (String s : res) if (s.toLowerCase(Locale.ROOT).startsWith(last)) out.add(s);
        return out;
    }

    // ---- Helfer ----

    protected Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        Msg.send(s, "only-players", "{prefix}&cDieser Befehl ist nur fuer Spieler.");
        return null;
    }

    protected Player online(CommandSender s, String name) {
        Player t = Bukkit.getPlayerExact(name);
        if (t == null || (s instanceof Player p && !p.canSee(t))) {
            Msg.send(s, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", name);
            return null;
        }
        return t;
    }

    /** Spieler, der schon einmal online war (ohne Mojang-Abfrage). */
    protected OfflinePlayer known(CommandSender s, String name) {
        OfflinePlayer op = Bukkit.getPlayerExact(name);
        if (op == null) op = Bukkit.getOfflinePlayerIfCached(name);
        if (op == null || (!op.isOnline() && !op.hasPlayedBefore())) {
            Msg.send(s, "player-unknown", "{prefix}&cSpieler &f{name} &cwar noch nie auf MineSMP.", "name", name);
            return null;
        }
        return op;
    }

    protected void usage(CommandSender s, String usage) {
        Msg.send(s, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", usage);
    }

    protected static List<String> list(String... s) {
        return Arrays.asList(s);
    }
}
