package de.minesmp.core.progress;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Erfolge aus achievements.yml, geprueft alle 30 Sekunden anhand von Statistiken. */
public final class AchievementService {
    public record Achievement(String id, String name, String description, String stat, double amount, Material icon, ConfigurationSection reward) {}

    private final MineSMP plugin;
    private final Map<String, Achievement> list = new LinkedHashMap<>();

    public AchievementService(MineSMP plugin) {
        this.plugin = plugin;
        YamlConfiguration y = plugin.yaml("achievements.yml");
        ConfigurationSection root = y.getConfigurationSection("achievements");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                Material icon = Material.matchMaterial(s.getString("icon", "GOLD_INGOT"));
                list.put(id, new Achievement(id, s.getString("name", id), s.getString("description", ""), s.getString("stat", "kills"),
                        s.getDouble("amount", 1), icon == null ? Material.GOLD_INGOT : icon, s.getConfigurationSection("reward")));
            }
        }
        plugin.command(new SimpleCommand("achievements", "minesmp.achievements", "Erfolge", (s, l, a) -> { if (s instanceof Player p) open(p); }, "erfolge"));
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::check), 200, 600);
    }

    /** Wert einer Statistik: kills, deaths, level, balance, best-streak, playtime-hours, duel-wins, event-wins, skill:mining, stat:blocks_broken */
    public double value(Player p, String stat) {
        PlayerData d = plugin.data().get(p);
        if (stat.startsWith("skill:")) return plugin.skills().skillLevel(d, stat.substring(6));
        if (stat.startsWith("stat:")) return d.stat(stat.substring(5));
        return switch (stat) {
            case "kills" -> d.kills;
            case "deaths" -> d.deaths;
            case "level" -> d.level;
            case "balance" -> d.balance;
            case "best-streak" -> d.bestStreak;
            case "playtime-hours" -> p.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 20.0 / 3600.0;
            case "duel-wins" -> d.duelWins;
            case "event-wins" -> d.eventWins;
            case "daily-streak" -> d.dailyStreak;
            default -> 0;
        };
    }

    public void check(Player p) {
        PlayerData d = plugin.data().get(p);
        for (Achievement a : list.values()) {
            if (d.achievements.contains(a.id) || value(p, a.stat) < a.amount) continue;
            d.achievements.add(a.id);
            d.dirty = true;
            Msg.broadcast("achievement.unlocked", "{prefix}&f{name} &7hat den Erfolg &6&l{achievement} &7freigeschaltet!", "name", p.getName(), "achievement", Text.strip(a.name));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1, 1);
            plugin.rewards().give(p, a.reward, a.name);
        }
    }

    public int unlocked(Player p) {
        return plugin.data().get(p).achievements.size();
    }

    public int total() {
        return list.size();
    }

    public void open(Player p) {
        PlayerData d = plugin.data().get(p);
        Menu m = new Menu(6, "&8Erfolge &7(" + d.achievements.size() + "/" + list.size() + ")") {};
        int i = 0;
        for (Achievement a : list.values()) {
            if (i >= 54) break;
            boolean done = d.achievements.contains(a.id);
            double v = Math.min(a.amount, value(p, a.stat));
            List<String> lore = new ArrayList<>();
            lore.add("&7" + a.description);
            lore.add("");
            lore.add(done ? "&a✔ Freigeschaltet" : "&7Fortschritt: &f" + Text.money(v) + "&7/&f" + Text.money(a.amount));
            if (!done) lore.add(Text.progressBar(v / a.amount, 20, "&6", "&8"));
            m.set(i++, Items.of(done ? a.icon : Material.GRAY_DYE, 1, (done ? "&6" : "&7") + Text.strip(a.name), lore));
        }
        m.open(p);
    }
}
