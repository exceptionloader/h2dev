package de.minesmp.core.utility;

import de.minesmp.core.MineSMP;
import de.minesmp.core.clan.Clan;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** /profile, /stats, /settings */
public final class ProfileService {
    private final MineSMP plugin;

    private record Setting(String key, String name, Material icon, boolean def, String permission) {}

    private final List<Setting> settings = List.of(
            new Setting("scoreboard", "Scoreboard", Material.PAINTING, true, null),
            new Setting("bossbar", "Info-Leiste oben", Material.DRAGON_HEAD, true, null),
            new Setting("actionbar", "Info ueber der Hotbar", Material.COMPASS, true, null),
            new Setting("tpa", "Teleport-Anfragen", Material.ENDER_PEARL, true, null),
            new Setting("msg", "Private Nachrichten", Material.WRITABLE_BOOK, true, null),
            new Setting("pay", "Zahlungen empfangen", Material.GOLD_INGOT, true, null),
            new Setting("duels", "Duell-Anfragen", Material.IRON_SWORD, true, null),
            new Setting("mentions", "Erwaehnungs-Ton", Material.NOTE_BLOCK, true, null),
            new Setting("join-messages", "Join-/Quit-Nachrichten", Material.OAK_DOOR, true, null),
            new Setting("nametag-self", "Eigenes Namensschild (F5)", Material.NAME_TAG, true, null),
            new Setting("spawn-compass", "Navigator-Kompass am Spawn", Material.COMPASS, true, null),
            new Setting("rank-particles", "Meine Rang-Partikel", Material.FIREWORK_STAR, true, null),
            new Setting("show-particles", "Partikel anderer Spieler", Material.ENDER_EYE, true, null),
            new Setting("socialspy", "SocialSpy (Team)", Material.SPYGLASS, false, "minesmp.staff.socialspy"));

    public ProfileService(MineSMP plugin) {
        this.plugin = plugin;
        plugin.command(new SimpleCommand("profile", "minesmp.profile", "Spielerprofil", (s, l, a) -> {
            if (!(s instanceof Player p)) return;
            Player t = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : p;
            if (t == null) { Msg.send(p, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a[0]); return; }
            openProfile(p, t);
        }, "profil"));
        plugin.command(new SimpleCommand("stats", "minesmp.stats", "Statistiken", this::stats, "statistiken"));
        plugin.command(new SimpleCommand("settings", "minesmp.settings", "Einstellungen", (s, l, a) -> { if (s instanceof Player p) openSettings(p); }, "einstellungen", "options"));
    }

    private void stats(CommandSender s, String l, String[] a) {
        Player t = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : s instanceof Player p ? p : null;
        if (t == null) { Msg.send(s, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a.length > 0 ? a[0] : "?"); return; }
        PlayerData d = plugin.data().get(t);
        double kd = d.deaths == 0 ? d.kills : (double) d.kills / d.deaths;
        Msg.send(s, "stats.body", "&8&m-----&r &bStatistiken von &f{name} &8&m-----\n&7Kills: &c{kills} &8| &7Tode: &f{deaths} &8| &7K/D: &e{kd}\n"
                        + "&7Killstreak: &c{streak} &8(Rekord {best})\n&7Level: &e{level} &8| &7Geld: &a{money}\n&7Duelle: &a{dw}W &c{dl}L &8| &7Event-Siege: &6{ew}\n"
                        + "&7Bloecke abgebaut: &f{blocks} &8| &7Fische: &f{fish} &8| &7Mobs: &f{mobs}\n&7Spielzeit: &b{playtime} &8| &7Erfolge: &6{ach}/{achTotal}",
                "name", t.getName(), "kills", String.valueOf(d.kills), "deaths", String.valueOf(d.deaths), "kd", String.format(java.util.Locale.GERMANY, "%.2f", kd),
                "streak", String.valueOf(d.killstreak), "best", String.valueOf(d.bestStreak), "level", String.valueOf(d.level), "money", plugin.eco().format(d.balance),
                "dw", String.valueOf(d.duelWins), "dl", String.valueOf(d.duelLosses), "ew", String.valueOf(d.eventWins),
                "blocks", Text.money(d.stat("blocks_broken")), "fish", Text.money(d.stat("fish_caught")), "mobs", Text.money(d.stat("mobs_killed")),
                "playtime", TimeUtil.format(t.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L * 1000L),
                "ach", String.valueOf(plugin.achievements().unlocked(t)), "achTotal", String.valueOf(plugin.achievements().total()));
    }

    public void openProfile(Player viewer, Player t) {
        PlayerData d = plugin.data().get(t);
        Clan c = plugin.clans().of(t);
        Menu m = new Menu(5, "&8Profil: &b" + t.getName()) {};
        m.set(4, Items.head(t, "&b&l" + t.getName(), List.of(
                "&7Rang: " + plugin.chat().prefix(t),
                "&7Erster Join: &f" + new SimpleDateFormat("dd.MM.yyyy").format(new Date(d.firstJoin == 0 ? System.currentTimeMillis() : d.firstJoin)),
                "&7Spielzeit: &f" + TimeUtil.format(t.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L * 1000L))));
        m.set(19, Items.of(Material.DIAMOND_SWORD, 1, "&cPvP", List.of("&7Kills: &f" + d.kills, "&7Tode: &f" + d.deaths,
                "&7Killstreak: &f" + d.killstreak + " &8(Rekord " + d.bestStreak + ")", "&7Duelle: &a" + d.duelWins + "W &c" + d.duelLosses + "L",
                "&7Kopfgeld: &a" + plugin.eco().format(plugin.bounties().get(t.getUniqueId())))));
        m.set(21, Items.of(Material.EXPERIENCE_BOTTLE, 1, "&eLevel " + d.level, List.of("&7Klicke fuer Skills")), e -> plugin.skills().open(viewer));
        m.set(23, Items.of(Material.GOLD_INGOT, 1, "&6Wirtschaft", List.of("&7Kontostand: &a" + plugin.eco().format(d.balance))));
        m.set(25, Items.of(Material.BLUE_BANNER, 1, "&bClan", List.of(c == null ? "&7Kein Clan" : "&f" + c.name + " &8[&b" + c.tag + "&8]",
                c == null ? "" : "&7Rang: " + c.rank(t.getUniqueId()).display)));
        m.set(31, Items.of(Material.GOLDEN_APPLE, 1, "&6Erfolge", List.of("&f" + plugin.achievements().unlocked(t) + "&7/&f" + plugin.achievements().total(), "&7Klicke zum Ansehen")),
                e -> { if (viewer.equals(t)) plugin.achievements().open(viewer); });
        if (viewer.equals(t)) m.set(40, Items.of(Material.COMPARATOR, 1, "&7Einstellungen", List.of("&7/settings")), e -> openSettings(viewer));
        m.fillEmpty();
        m.open(viewer);
    }

    public void openSettings(Player p) {
        PlayerData d = plugin.data().get(p);
        List<Setting> visible = new ArrayList<>();
        for (Setting s : settings) if (s.permission == null || p.hasPermission(s.permission)) visible.add(s);
        Menu m = new Menu(3, "&8Einstellungen") {};
        int slot = 10;
        for (Setting s : visible) {
            if (slot == 17) slot = 19;
            boolean on = d.setting(s.key, s.def);
            m.set(slot++, Items.of(s.icon, 1, (on ? "&a" : "&c") + s.name, List.of(on ? "&aAktiviert" : "&cDeaktiviert", "&7Klicke zum Umschalten")), e -> {
                d.settings.put(s.key, !d.setting(s.key, s.def));
                d.dirty = true;
                if (s.key.equals("scoreboard")) plugin.scoreboard().update(p);
                openSettings(p);
            });
        }
        m.fillEmpty();
        m.open(p);
    }
}
