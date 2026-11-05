package de.minesmp.core.admin;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Anti-Bot (Join-Flut-Erkennung, Accounts pro IP) und Schutz vor Command-Exploits
 * (Namespace-Befehle wie /minecraft:op, Plugin-Liste, versteckte Tab-Vervollstaendigung).
 */
public final class SecurityService implements Listener {
    private final MineSMP plugin;
    private final Deque<Long> recentNewJoins = new ArrayDeque<>();
    private volatile long lockdownUntil;
    private final Set<String> blocked = new HashSet<>();

    public SecurityService(MineSMP plugin) {
        this.plugin = plugin;
        for (String c : plugin.getConfig().getStringList("security.blocked-commands")) blocked.add(c.toLowerCase(Locale.ROOT));
        plugin.listen(this);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        var cfg = plugin.getConfig();
        boolean known = Bukkit.getOfflinePlayer(e.getUniqueId()).hasPlayedBefore();
        long now = System.currentTimeMillis();
        if (!known) {
            synchronized (recentNewJoins) {
                recentNewJoins.addLast(now);
                long window = cfg.getLong("security.antibot.window-seconds", 10) * 1000;
                while (!recentNewJoins.isEmpty() && recentNewJoins.peekFirst() < now - window) recentNewJoins.pollFirst();
                if (recentNewJoins.size() > cfg.getInt("security.antibot.max-new-joins", 8) && lockdownUntil < now) {
                    lockdownUntil = now + cfg.getLong("security.antibot.lockdown-seconds", 120) * 1000;
                    plugin.getLogger().warning("Anti-Bot: Join-Flut erkannt, neue Spieler werden fuer kurze Zeit abgelehnt.");
                    plugin.webhook().send("server", "Anti-Bot aktiviert", "Join-Flut erkannt: " + recentNewJoins.size() + " neue Spieler in kurzer Zeit.", 0xFFA000);
                }
            }
            if (lockdownUntil > now) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Text.c(Msg.get("antibot.kick",
                        "&b&lMine&f&lSMP\n\n&cDer Server ist gerade im Schutzmodus.\n&7Bitte versuche es in einer Minute erneut.")));
                return;
            }
        }
        int maxPerIp = cfg.getInt("security.max-accounts-per-ip", 3);
        if (maxPerIp > 0) {
            String ip = e.getAddress().getHostAddress();
            long same = Bukkit.getOnlinePlayers().stream().filter(p -> p.getAddress() != null && p.getAddress().getAddress().getHostAddress().equals(ip)).count();
            if (same >= maxPerIp) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Text.c(Msg.get("antibot.ip-limit", "&cZu viele Accounts von deiner IP-Adresse online.")));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String msg = e.getMessage();
        String label = msg.length() > 1 ? msg.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT) : "";
        if (p.hasPermission("minesmp.staff.commands")) return;
        if (label.contains(":") || blocked.contains(label)) {
            e.setCancelled(true);
            Msg.send(p, "unknown-command", "{prefix}&cUnbekannter Befehl. Nutze &f/help&c.");
        }
    }

    @EventHandler
    public void onCommandSend(PlayerCommandSendEvent e) {
        if (e.getPlayer().hasPermission("minesmp.staff.commands")) return;
        e.getCommands().removeIf(c -> c.contains(":") || blocked.contains(c.toLowerCase(Locale.ROOT)));
    }
}
