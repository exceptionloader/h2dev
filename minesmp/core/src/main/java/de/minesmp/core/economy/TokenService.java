package de.minesmp.core.economy;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Zweitwaehrungen neben MineCoins:
 * GEMS  - Premium-Waehrung (Store, Events, Votes) fuer Ranks, Schluessel, Spawner.
 * STARS - Sterne fuer aktive Spielzeit und Belohnungen, fuer den Sterne-Shop.
 * Offline-Gutschriften landen in der kv-Tabelle und werden beim naechsten Join verbucht.
 */
public final class TokenService implements Listener {
    public enum Kind {
        GEMS("gems", "Gems", "&d", "◆"), STARS("stars", "Sterne", "&e", "✯");
        public final String id, display, color, symbol;
        Kind(String id, String display, String color, String symbol) { this.id = id; this.display = display; this.color = color; this.symbol = symbol; }
        public static Kind of(String s) {
            if (s == null) return null;
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "gems", "gem" -> GEMS;
                case "stars", "star", "sterne", "stern" -> STARS;
                default -> null;
            };
        }
    }

    private final MineSMP plugin;

    public TokenService(MineSMP plugin) {
        this.plugin = plugin;
        plugin.listen(this);
        plugin.command(new SimpleCommand("gems", "minesmp.gems", "Deine Gems", (s, l, a) -> cmd(s, a, Kind.GEMS), this::complete, "gem"));
        plugin.command(new SimpleCommand("sterne", "minesmp.stars", "Deine Sterne", (s, l, a) -> cmd(s, a, Kind.STARS), this::complete, "stars", "stern"));
        // Sterne fuer aktive Spielzeit (AFK zaehlt nicht)
        Bukkit.getScheduler().runTaskTimer(plugin, this::minute, 20 * 60, 20 * 60);
    }

    private List<String> complete(CommandSender s, String[] a) {
        if (a.length == 1) return s.hasPermission("minesmp.admin.tokens") ? List.of("pay", "shop", "give", "take", "set") : List.of("pay", "shop");
        if (a.length == 2) return null;
        return List.of();
    }

    public String format(Kind k, long n) {
        return k.color + Text.money(n) + " " + k.display;
    }

    public long get(Player p, Kind k) {
        PlayerData d = plugin.data().get(p);
        return k == Kind.GEMS ? d.gems : d.stars;
    }

    public boolean has(Player p, Kind k, long n) {
        return get(p, k) >= n;
    }

    public boolean take(Player p, Kind k, long n, String reason) {
        if (n < 0) return false;
        PlayerData d = plugin.data().get(p);
        long cur = k == Kind.GEMS ? d.gems : d.stars;
        if (cur < n) return false;
        if (k == Kind.GEMS) d.gems -= n; else d.stars -= n;
        d.dirty = true;
        plugin.eco().log(p.getUniqueId(), null, n, k.name() + "_SPEND", reason);
        return true;
    }

    /** Gutschrift fuer online oder offline Spieler. */
    public void give(UUID id, Kind k, long n, String reason) {
        if (n <= 0) return;
        PlayerData d = plugin.data().getIfLoaded(id);
        plugin.eco().log(null, id, n, k.name() + "_GET", reason);
        if (d != null) {
            if (k == Kind.GEMS) d.gems += n; else d.stars += n;
            d.dirty = true;
            return;
        }
        String key = "pending-" + k.id + ":" + id;
        plugin.db().run(c -> {
            long cur = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM kv WHERE k = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) cur = Long.parseLong(rs.getString(1)); }
            } catch (NumberFormatException ignored) { }
            try (PreparedStatement ps = c.prepareStatement("REPLACE INTO kv (k, v) VALUES (?, ?)")) {
                ps.setString(1, key);
                ps.setString(2, String.valueOf(cur + n));
                ps.executeUpdate();
            }
        });
    }

    private void set(Player p, Kind k, long n) {
        PlayerData d = plugin.data().get(p);
        if (k == Kind.GEMS) d.gems = Math.max(0, n); else d.stars = Math.max(0, n);
        d.dirty = true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        for (Kind k : Kind.values()) {
            String key = "pending-" + k.id + ":" + id;
            plugin.db().queryThen(c -> {
                String v = null;
                try (PreparedStatement ps = c.prepareStatement("SELECT v FROM kv WHERE k = ?")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) v = rs.getString(1); }
                }
                if (v != null) try (PreparedStatement ps = c.prepareStatement("DELETE FROM kv WHERE k = ?")) { ps.setString(1, key); ps.executeUpdate(); }
                return v;
            }, v -> {
                if (v == null) return;
                long n;
                try { n = Long.parseLong(v); } catch (NumberFormatException ex) { return; }
                Player p = Bukkit.getPlayer(id);
                if (p == null) { give(id, k, n, "Nachtrag"); return; }
                PlayerData d = plugin.data().get(p);
                if (k == Kind.GEMS) d.gems += n; else d.stars += n;
                d.dirty = true;
                Msg.send(p, "tokens.offline", "{prefix}&7Waehrend du offline warst, hast du &f{amount} &7erhalten.", "amount", format(k, n));
            });
        }
    }

    private void minute() {
        int every = plugin.getConfig().getInt("tokens.stars.minutes-per-reward", 30);
        int amount = plugin.getConfig().getInt("tokens.stars.playtime-reward", 1);
        if (every <= 0 || amount <= 0) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.afk().isAfk(p)) continue;
            PlayerData d = plugin.data().get(p);
            if (++d.starMinutes < every) { d.dirty = true; continue; }
            d.starMinutes = 0;
            int bonus = 0;
            for (int i = 10; i >= 1; i--) if (p.hasPermission("minesmp.stars.bonus." + i)) { bonus = i; break; }
            d.stars += amount + bonus;
            d.dirty = true;
            Msg.send(p, "tokens.playtime", "{prefix}&eDanke fuers Spielen! &7Du erhaeltst &e+{n} Sterne &7fuer {min} Minuten Spielzeit.",
                    "n", String.valueOf(amount + bonus), "min", String.valueOf(every));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        }
    }

    private void cmd(CommandSender s, String[] a, Kind k) {
        if (a.length == 0 || a[0].equalsIgnoreCase("shop")) {
            if (a.length == 0 && !(s instanceof Player)) { Msg.send(s, "only-players", "{prefix}&cDieser Befehl ist nur fuer Spieler."); return; }
            if (s instanceof Player p) {
                if (a.length == 0) Msg.send(p, "tokens.balance", "{prefix}&7Du hast &f{amount}&7.", "amount", format(k, get(p, k)));
                plugin.tokenShops().open(p, k.id);
            }
            return;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("pay")) {
            if (!(s instanceof Player p)) return;
            if (!plugin.getConfig().getBoolean("tokens." + k.id + ".allow-pay", k == Kind.GEMS)) { Msg.send(p, "tokens.nopay", "{prefix}&c{name} koennen nicht ueberwiesen werden.", "name", k.display); return; }
            if (a.length < 3) { Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/" + k.id + " pay <Spieler> <Anzahl>"); return; }
            Player t = Bukkit.getPlayerExact(a[1]);
            long n = parse(a[2]);
            if (t == null || t == p || n <= 0) { Msg.send(p, "tokens.invalid", "{prefix}&cSpieler oder Anzahl ungueltig."); return; }
            if (!take(p, k, n, "Pay an " + t.getName())) { Msg.send(p, "tokens.notenough", "{prefix}&cDu hast nicht genug {name}.", "name", k.display); return; }
            give(t.getUniqueId(), k, n, "Pay von " + p.getName());
            Msg.send(p, "tokens.paid", "{prefix}&aDu hast &f{name} {amount} &agesendet.", "name", t.getName(), "amount", format(k, n));
            Msg.send(t, "tokens.received", "{prefix}&aDu hast {amount} &avon &f{name} &aerhalten.", "name", p.getName(), "amount", format(k, n));
            return;
        }
        if (!s.hasPermission("minesmp.admin.tokens")) {
            if (a.length == 1 && s.hasPermission("minesmp.tokens.others")) {
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t != null) Msg.send(s, "tokens.other", "{prefix}&f{name} &7hat &f{amount}&7.", "name", t.getName(), "amount", format(k, get(t, k)));
                return;
            }
            Msg.send(s, "no-permission", "{prefix}&cDazu hast du keine Berechtigung.");
            return;
        }
        if (a.length < 3) {
            if (a.length == 1) {
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t != null) { Msg.send(s, "tokens.other", "{prefix}&f{name} &7hat &f{amount}&7.", "name", t.getName(), "amount", format(k, get(t, k))); return; }
            }
            Msg.send(s, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/" + k.id + " <give|take|set> <Spieler> <Anzahl>");
            return;
        }
        long n = parse(a[2]);
        if (n < 0) { Msg.send(s, "tokens.invalid", "{prefix}&cSpieler oder Anzahl ungueltig."); return; }
        Player t = Bukkit.getPlayerExact(a[1]);
        switch (sub) {
            case "give" -> {
                if (t == null) {
                    OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(a[1]);
                    if (op == null || !op.hasPlayedBefore()) { Msg.send(s, "player-unknown", "{prefix}&cSpieler &f{name} &cwar noch nie auf MineSMP.", "name", a[1]); return; }
                    give(op.getUniqueId(), k, n, "Admin " + s.getName());
                } else {
                    give(t.getUniqueId(), k, n, "Admin " + s.getName());
                    Msg.send(t, "tokens.received-admin", "{prefix}&aDu hast {amount} &aerhalten.", "amount", format(k, n));
                }
            }
            case "take" -> {
                if (t == null) { Msg.send(s, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a[1]); return; }
                set(t, k, get(t, k) - n);
            }
            case "set" -> {
                if (t == null) { Msg.send(s, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a[1]); return; }
                set(t, k, n);
            }
            default -> { Msg.send(s, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/" + k.id + " <give|take|set> <Spieler> <Anzahl>"); return; }
        }
        plugin.audit().log(s, k.name() + "_" + sub.toUpperCase(Locale.ROOT), a[1] + " " + n);
        Msg.send(s, "tokens.admin-done", "{prefix}&aErledigt: &f{sub} {amount} &afuer &f{name}&a.", "sub", sub, "amount", format(k, n), "name", a[1]);
    }

    private static long parse(String s) {
        if (s.equals("0")) return 0;
        try {
            double d = Text.parseAmount(s);
            return d < 0 || Double.isNaN(d) ? -1 : (long) Math.floor(d);
        } catch (Exception e) {
            return -1;
        }
    }
}
