package de.minesmp.core.admin;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Ban-, Tempban-, Mute-, Kick- und Warn-System mit Verlauf und Discord-Webhook. */
public final class PunishmentService implements Listener {
    public record Mute(long expires, String reason) {
        public boolean active() { return expires == 0 || expires > System.currentTimeMillis(); }
    }

    private final MineSMP plugin;
    private final Map<UUID, Mute> mutes = new ConcurrentHashMap<>();
    private final List<String> reasons = List.of("Hacking", "Beleidigung", "Spam", "Griefing", "Werbung", "Bugusing", "Teaming", "Unangemessener_Name");

    public PunishmentService(MineSMP plugin) {
        this.plugin = plugin;
        plugin.listen(this);
        SimpleCommand.Completer reasonComp = (s, a) -> a.length == 1 ? null : a.length == 2 ? List.of("1h", "1d", "7d", "30d", "perm") : reasons;
        SimpleCommand.Completer plainComp = (s, a) -> a.length == 1 ? null : reasons;
        plugin.command(new SimpleCommand("ban", "minesmp.staff.ban", "Bannt einen Spieler", (s, l, a) -> punish(s, a, "BAN", false), plainComp));
        plugin.command(new SimpleCommand("tempban", "minesmp.staff.tempban", "Bannt temporaer", (s, l, a) -> punish(s, a, "BAN", true), reasonComp));
        plugin.command(new SimpleCommand("unban", "minesmp.staff.unban", "Entbannt", (s, l, a) -> revoke(s, a, "BAN"), "pardon"));
        plugin.command(new SimpleCommand("mute", "minesmp.staff.mute", "Stummschalten", (s, l, a) -> punish(s, a, "MUTE", false), plainComp));
        plugin.command(new SimpleCommand("tempmute", "minesmp.staff.mute", "Temporaer stummschalten", (s, l, a) -> punish(s, a, "MUTE", true), reasonComp));
        plugin.command(new SimpleCommand("unmute", "minesmp.staff.mute", "Stummschaltung aufheben", (s, l, a) -> revoke(s, a, "MUTE")));
        plugin.command(new SimpleCommand("warn", "minesmp.staff.warn", "Verwarnt einen Spieler", (s, l, a) -> punish(s, a, "WARN", false), plainComp));
        plugin.command(new SimpleCommand("kick", "minesmp.staff.kick", "Kickt einen Spieler", this::kick, plainComp));
        plugin.command(new SimpleCommand("history", "minesmp.staff.history", "Strafverlauf", this::history, "strafen", "warns"));
    }

    // ---------------- Login/Join ----------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        try {
            String[] ban = plugin.db().query(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT reason, expires, staff_name FROM punishments WHERE target = ? AND type = 'BAN' AND active = 1 AND (expires = 0 OR expires > ?) ORDER BY id DESC LIMIT 1")) {
                    ps.setString(1, e.getUniqueId().toString());
                    ps.setLong(2, System.currentTimeMillis());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? new String[]{rs.getString(1), String.valueOf(rs.getLong(2)), rs.getString(3)} : null;
                    }
                }
            }).get(10, TimeUnit.SECONDS);
            if (ban != null) {
                long exp = Long.parseLong(ban[1]);
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, Text.c(Msg.get("ban.screen",
                        "&b&lMine&f&lSMP\n\n&cDu bist {duration} vom Server gebannt.\n&7Grund: &f{reason}\n&7Von: &f{staff}\n\n&7Entbannungsantrag: &b{discord}",
                        "duration", exp == 0 ? "&4permanent" : "fuer &f" + TimeUtil.format(exp - System.currentTimeMillis()),
                        "reason", ban[0], "staff", ban[2], "discord", plugin.getConfig().getString("links.discord", "discord.gg/minesmp"))));
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Ban-Pruefung fehlgeschlagen fuer " + e.getName() + ": " + ex.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        plugin.db().queryThen(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT reason, expires FROM punishments WHERE target = ? AND type = 'MUTE' AND active = 1 AND (expires = 0 OR expires > ?) ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, id.toString());
                ps.setLong(2, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Mute(rs.getLong(2), rs.getString(1)) : null;
                }
            }
        }, m -> { if (m != null) mutes.put(id, m); });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        mutes.remove(e.getPlayer().getUniqueId());
    }

    public Mute mute(Player p) {
        Mute m = mutes.get(p.getUniqueId());
        if (m != null && !m.active()) {
            mutes.remove(p.getUniqueId());
            return null;
        }
        return m;
    }

    // ---------------- Befehle ----------------

    private void punish(CommandSender s, String[] a, String type, boolean temp) {
        int need = temp ? 3 : 2;
        if (a.length < need) {
            Msg.send(s, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/" + type.toLowerCase() + " <Spieler> " + (temp ? "<Dauer z.B. 7d> " : "") + "<Grund>");
            return;
        }
        OfflinePlayer t = Bukkit.getPlayerExact(a[0]);
        if (t == null) t = Bukkit.getOfflinePlayerIfCached(a[0]);
        if (t == null) {
            Msg.send(s, "player-unknown", "{prefix}&cSpieler &f{name} &cwar noch nie auf MineSMP.", "name", a[0]);
            return;
        }
        if (t.isOnline() && t.getPlayer().hasPermission("minesmp.staff.bypass") && !s.hasPermission("minesmp.admin")) {
            Msg.send(s, "punish.bypass", "{prefix}&cDieser Spieler kann nicht bestraft werden.");
            return;
        }
        long duration = 0;
        if (temp) {
            duration = TimeUtil.parseDuration(a[1]);
            if (duration < 0) {
                Msg.send(s, "punish.bad-duration", "{prefix}&cUngueltige Dauer. Beispiele: &f30m, 12h, 7d, 1mo");
                return;
            }
        }
        String reason = String.join(" ", Arrays.copyOfRange(a, need - 1, a.length)).replace('_', ' ');
        apply(s, t, type, duration, reason);
    }

    /** Wendet eine Strafe an (auch fuer automatische Eskalation). duration 0 = permanent. */
    public void apply(CommandSender s, OfflinePlayer t, String type, long duration, String reason) {
        apply(s, s.getName(), t, type, duration, reason);
    }

    /** Wie oben, aber mit eigenem Anzeigenamen fuer den Ausloeser (z.B. "KI-System"). */
    public void apply(CommandSender s, String staffName, OfflinePlayer t, String type, long duration, String reason) {
        long now = System.currentTimeMillis();
        long expires = duration == 0 ? 0 : now + duration;
        String staffId = s instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
        String ip = t.isOnline() && t.getPlayer().getAddress() != null ? t.getPlayer().getAddress().getAddress().getHostAddress() : null;
        String dur = duration == 0 ? (type.equals("WARN") ? "-" : "permanent") : TimeUtil.format(duration);
        plugin.db().update("INSERT INTO punishments (target, target_name, type, reason, staff, staff_name, created, expires, active, ip) VALUES (?,?,?,?,?,?,?,?,?,?)",
                t.getUniqueId().toString(), t.getName(), type, reason, staffId, staffName, now, type.equals("WARN") ? 0 : expires, 1, ip);
        plugin.audit().log(staffName, type, t.getName() + " (" + dur + "): " + reason);
        plugin.webhook().send("punishments", type + ": " + t.getName(), "Grund: " + reason + "\nDauer: " + dur + "\nTeammitglied: " + staffName, 0xE53935);
        Player online = t.getPlayer();
        switch (type) {
            case "BAN" -> {
                if (online != null) online.kick(Text.c(Msg.get("ban.kick", "&b&lMine&f&lSMP\n\n&cDu wurdest gebannt.\n&7Grund: &f{reason}\n&7Dauer: &f{duration}",
                        "reason", reason, "duration", dur)));
                Msg.broadcast("ban.broadcast", "{prefix}&f{name} &7wurde von &b{staff} &7gebannt. &8(&f{reason}&8)", "name", t.getName(), "staff", staffName, "reason", reason);
            }
            case "MUTE" -> {
                if (online != null) {
                    mutes.put(online.getUniqueId(), new Mute(expires, reason));
                    Msg.send(online, "mute.you", "{prefix}&cDu wurdest stummgeschaltet. &7Grund: &f{reason} &7Dauer: &f{duration}", "reason", reason, "duration", dur);
                }
                Msg.send(s, "mute.done", "{prefix}&f{name} &awurde stummgeschaltet.", "name", t.getName());
            }
            case "WARN" -> {
                if (online != null) Msg.send(online, "warn.you", "{prefix}&cDu wurdest verwarnt! &7Grund: &f{reason}", "reason", reason);
                Msg.send(s, "warn.done", "{prefix}&f{name} &awurde verwarnt.", "name", t.getName());
                escalate(t);
            }
            default -> { }
        }
    }

    /** Automatische Eskalation nach Anzahl Verwarnungen (config: punishments.warn-escalation). */
    private void escalate(OfflinePlayer t) {
        plugin.db().queryThen(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM punishments WHERE target = ? AND type = 'WARN' AND active = 1")) {
                ps.setString(1, t.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
            }
        }, count -> {
            var sec = plugin.getConfig().getConfigurationSection("punishments.warn-escalation." + count);
            if (sec == null) return;
            long d = TimeUtil.parseDuration(sec.getString("duration", "1h"));
            apply(Bukkit.getConsoleSender(), t, sec.getString("type", "MUTE").toUpperCase(), Math.max(0, d), "Automatisch: " + count + " Verwarnungen");
        });
    }

    private void revoke(CommandSender s, String[] a, String type) {
        if (a.length < 1) {
            Msg.send(s, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/un" + type.toLowerCase() + " <Spieler>");
            return;
        }
        OfflinePlayer t = Bukkit.getPlayerExact(a[0]);
        if (t == null) t = Bukkit.getOfflinePlayerIfCached(a[0]);
        if (t == null) {
            Msg.send(s, "player-unknown", "{prefix}&cSpieler &f{name} &cwar noch nie auf MineSMP.", "name", a[0]);
            return;
        }
        UUID id = t.getUniqueId();
        String name = t.getName();
        plugin.db().update("UPDATE punishments SET active = 0 WHERE target = ? AND type = ?", id.toString(), type);
        if (type.equals("MUTE")) {
            mutes.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) Msg.send(p, "mute.removed", "{prefix}&aDeine Stummschaltung wurde aufgehoben.");
        }
        plugin.audit().log(s, "UN" + type, name);
        plugin.webhook().send("punishments", "UN" + type + ": " + name, "Teammitglied: " + s.getName(), 0x43A047);
        Msg.send(s, "revoke.done", "{prefix}&aStrafe ({type}) fuer &f{name} &aaufgehoben.", "type", type, "name", name);
    }

    private void kick(CommandSender s, String l, String[] a) {
        if (a.length < 1) {
            Msg.send(s, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/kick <Spieler> [Grund]");
            return;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) {
            Msg.send(s, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a[0]);
            return;
        }
        String reason = a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)).replace('_', ' ') : "Kein Grund angegeben";
        t.kick(Text.c(Msg.get("kick.screen", "&b&lMine&f&lSMP\n\n&cDu wurdest gekickt.\n&7Grund: &f{reason}", "reason", reason)));
        plugin.db().update("INSERT INTO punishments (target, target_name, type, reason, staff, staff_name, created, expires, active) VALUES (?,?,?,?,?,?,?,?,0)",
                t.getUniqueId().toString(), t.getName(), "KICK", reason, s instanceof Player p ? p.getUniqueId().toString() : "CONSOLE", s.getName(), System.currentTimeMillis(), 0);
        plugin.audit().log(s, "KICK", t.getName() + ": " + reason);
        Msg.send(s, "kick.done", "{prefix}&f{name} &awurde gekickt.", "name", t.getName());
    }

    private void history(CommandSender s, String l, String[] a) {
        if (a.length < 1) {
            Msg.send(s, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/history <Spieler>");
            return;
        }
        OfflinePlayer t = Bukkit.getPlayerExact(a[0]);
        if (t == null) t = Bukkit.getOfflinePlayerIfCached(a[0]);
        if (t == null) {
            Msg.send(s, "player-unknown", "{prefix}&cSpieler &f{name} &cwar noch nie auf MineSMP.", "name", a[0]);
            return;
        }
        String id = t.getUniqueId().toString();
        String name = t.getName();
        plugin.db().queryThen(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT type, reason, staff_name, created, expires, active FROM punishments WHERE target = ? ORDER BY id DESC LIMIT 20")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    SimpleDateFormat f = new SimpleDateFormat("dd.MM.yy HH:mm");
                    while (rs.next()) {
                        boolean act = rs.getInt(6) == 1 && (rs.getLong(5) == 0 || rs.getLong(5) > System.currentTimeMillis());
                        out.add("&8" + f.format(new Date(rs.getLong(4))) + " " + (act ? "&c" : "&7") + rs.getString(1) + " &f" + rs.getString(2) + " &8(" + rs.getString(3) + ")");
                    }
                }
            }
            return out;
        }, lines -> {
            Msg.send(s, "history.header", "&8&m----&r &bStrafverlauf von &f{name} &8&m----", "name", name);
            if (lines.isEmpty()) Msg.send(s, "history.empty", "&aKeine Eintraege.");
            lines.forEach(x -> s.sendMessage(Text.c(x)));
        });
    }
}
