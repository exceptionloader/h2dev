package de.minesmp.core.display;

import de.minesmp.core.MineSMP;
import de.minesmp.core.clan.Clan;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interne Platzhalter fuer Scoreboard/Tab: %online%, %balance%, %kills% ...
 * Dieselben Werte gibt es ueber PlaceholderAPI als %minesmp_<name>%.
 */
public final class Placeholders {
    private static final Pattern P = Pattern.compile("%([a-z0-9_]+)%");
    private final MineSMP plugin;
    private final boolean papi;

    public Placeholders(MineSMP plugin) {
        this.plugin = plugin;
        this.papi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    public String value(Player p, String key) {
        PlayerData d = plugin.data().get(p);
        Clan c = plugin.clans().of(p);
        String k = key.toLowerCase(Locale.ROOT);
        if (k.startsWith("keys_")) return String.valueOf(d.keys(k.substring(5)));
        if (k.startsWith("skill_")) return String.valueOf(plugin.skills().skillLevel(d, k.substring(6)));
        return switch (k) {
            case "online" -> String.valueOf(Bukkit.getOnlinePlayers().size());
            case "max_players" -> String.valueOf(Bukkit.getMaxPlayers());
            case "player", "name" -> p.getName();
            case "balance", "money" -> Text.compact(d.balance);
            case "balance_exact" -> Text.money(d.balance);
            case "kills" -> String.valueOf(d.kills);
            case "deaths" -> String.valueOf(d.deaths);
            case "kd" -> String.format(Locale.GERMANY, "%.2f", d.deaths == 0 ? d.kills : (double) d.kills / d.deaths);
            case "killstreak" -> String.valueOf(d.killstreak);
            case "level" -> String.valueOf(d.level);
            case "clan" -> c == null ? "-" : c.name;
            case "clan_tag" -> c == null ? "" : c.tag;
            case "clan_level" -> c == null ? "-" : String.valueOf(c.level);
            case "rank" -> Text.strip(plugin.chat().prefix(p)).trim();
            case "prefix" -> plugin.chat().prefix(p);
            case "playtime" -> TimeUtil.format(p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L * 1000L);
            case "ping" -> String.valueOf(p.getPing());
            case "tps" -> String.format(Locale.US, "%.1f", Math.min(20.0, Bukkit.getTPS()[0]));
            case "bounty" -> Text.compact(plugin.bounties().get(p.getUniqueId()));
            case "event" -> plugin.events().activeName() == null ? "-" : plugin.events().activeName();
            case "combat" -> plugin.combat().isTagged(p) ? (plugin.combat().remaining(p) / 1000 + 1) + "s" : "-";
            case "bp_tier" -> String.valueOf(plugin.battlepass().tier(p));
            case "daily_streak" -> String.valueOf(d.dailyStreak);
            case "afk" -> plugin.afk().isAfk(p) ? "AFK" : "";
            case "coords" -> p.getLocation().getBlockX() + " " + p.getLocation().getBlockY() + " " + p.getLocation().getBlockZ();
            case "x" -> String.valueOf(p.getLocation().getBlockX());
            case "y" -> String.valueOf(p.getLocation().getBlockY());
            case "z" -> String.valueOf(p.getLocation().getBlockZ());
            case "direction" -> direction(p.getLocation().getYaw());
            case "world" -> worldName(p.getWorld().getName());
            case "health" -> String.valueOf((int) Math.ceil(p.getHealth()));
            case "time" -> java.time.LocalTime.now(de.minesmp.core.utility.RewardService.ZONE).withSecond(0).withNano(0).toString();
            case "next_event" -> { var n = plugin.events().nextEvent(); yield n == null ? "-" : n.name(); }
            case "next_event_in" -> { var n = plugin.events().nextEvent(); yield n == null ? "-" : TimeUtil.format(n.inMillis() >= 3_600_000 ? n.inMillis() - n.inMillis() % 60_000 : n.inMillis()); }
            default -> null;
        };
    }

    private static String direction(float yaw) {
        String[] d = {"S", "SW", "W", "NW", "N", "NO", "O", "SO"};
        return d[Math.floorMod(Math.round(yaw / 45f), 8)];
    }

    private String worldName(String w) {
        String n = plugin.getConfig().getString("world-names." + w);
        return n != null ? n : w;
    }

    public String apply(Player p, String text) {
        if (text == null) return "";
        Matcher m = P.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = value(p, m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(sb);
        String out = sb.toString();
        if (papi && out.indexOf('%') >= 0) out = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, out);
        return out;
    }
}
