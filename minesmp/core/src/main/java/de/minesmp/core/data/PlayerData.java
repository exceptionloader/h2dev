package de.minesmp.core.data;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Alle persistenten Spielerdaten. Haeufig abgefragte Werte (Geld, Kills, ...) liegen
 * zusaetzlich in eigenen Spalten, der Rest als YAML-Block in "data".
 * Zugriff nur vom Hauptthread (ausser beim Laden/Speichern ueber Snapshots).
 */
public final class PlayerData {
    public final UUID uuid;
    public String name;
    public double balance;
    public int kills, deaths, killstreak, bestStreak;
    public int level = 1;
    public double xp;
    public long playtime;
    public String lastIp;
    public long firstJoin;

    public final Map<String, Double> skillXp = new HashMap<>();
    public final Map<String, Integer> keys = new HashMap<>();
    public final Map<String, String> homes = new LinkedHashMap<>();
    public final Map<String, Boolean> settings = new HashMap<>();
    public final Set<String> ignored = new HashSet<>();
    public final Map<String, Long> stats = new HashMap<>();
    public final Map<String, Long> cooldowns = new HashMap<>();
    public final Set<String> achievements = new HashSet<>();

    public int dailyStreak;
    public long lastDaily;

    public String questDay = "";
    public final Map<String, Integer> dailyQuests = new LinkedHashMap<>();
    public final Set<String> dailyDone = new HashSet<>();
    public String questWeek = "";
    public final Map<String, Integer> weeklyQuests = new LinkedHashMap<>();
    public final Set<String> weeklyDone = new HashSet<>();

    public String bpSeason = "";
    public int bpXp;
    public final Set<Integer> bpFree = new HashSet<>();
    public final Set<Integer> bpPremium = new HashSet<>();

    public String trail = "";
    public String pet = "";
    public int extraHearts;
    public int eventWins;
    public int duelWins, duelLosses;
    public final Set<Integer> levelRewards = new HashSet<>();
    /** Gesichertes Inventar (z.B. bei Duell-Absturz), wird beim naechsten Join zurueckgegeben. */
    public String savedInventory = "";
    /** Zweitwaehrungen: Gems (Premium) und Sterne (Spielzeit/Belohnungen). */
    /** Eigene Anordnung der Kit-Items (Kit-Editor): Kit -> Inventar-Slot je Item, kommagetrennt. */
    public final Map<String, String> kitLayouts = new HashMap<>();
    /** PvE: Abschluesse und heute belohnte Laeufe ("Tag:Anzahl"). */
    public int pveWins;
    public String pveRewarded = "";
    public long gems, stars;
    public int starMinutes;

    public transient boolean dirty;

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public boolean setting(String key, boolean def) {
        return settings.getOrDefault(key, def);
    }

    public long stat(String key) {
        return stats.getOrDefault(key, 0L);
    }

    public void addStat(String key, long n) {
        stats.merge(key, n, Long::sum);
        dirty = true;
    }

    public double skill(String key) {
        return skillXp.getOrDefault(key, 0.0);
    }

    public int keys(String crate) {
        return keys.getOrDefault(crate.toLowerCase(), 0);
    }

    public String serialize() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("xp", xp);
        y.set("killstreak", killstreak);
        y.set("best-streak", bestStreak);
        y.set("first-join", firstJoin);
        skillXp.forEach((k, v) -> y.set("skills." + k, v));
        keys.forEach((k, v) -> y.set("keys." + k, v));
        homes.forEach((k, v) -> y.set("homes." + k, v));
        settings.forEach((k, v) -> y.set("settings." + k, v));
        y.set("ignored", ignored.stream().toList());
        stats.forEach((k, v) -> y.set("stats." + k, v));
        long now = System.currentTimeMillis();
        cooldowns.forEach((k, v) -> { if (v > now) y.set("cooldowns." + k, v); });
        y.set("achievements", achievements.stream().toList());
        y.set("daily.streak", dailyStreak);
        y.set("daily.last", lastDaily);
        y.set("quests.day", questDay);
        dailyQuests.forEach((k, v) -> y.set("quests.daily." + k, v));
        y.set("quests.daily-done", dailyDone.stream().toList());
        y.set("quests.week", questWeek);
        weeklyQuests.forEach((k, v) -> y.set("quests.weekly." + k, v));
        y.set("quests.weekly-done", weeklyDone.stream().toList());
        y.set("bp.season", bpSeason);
        y.set("bp.xp", bpXp);
        y.set("bp.free", bpFree.stream().toList());
        y.set("bp.premium", bpPremium.stream().toList());
        y.set("cosmetic.trail", trail);
        y.set("cosmetic.pet", pet);
        y.set("extra-hearts", extraHearts);
        y.set("event-wins", eventWins);
        y.set("duel.wins", duelWins);
        y.set("duel.losses", duelLosses);
        y.set("level-rewards", levelRewards.stream().toList());
        y.set("saved-inventory", savedInventory);
        kitLayouts.forEach((k, v) -> y.set("kit-layouts." + k, v));
        y.set("pve.wins", pveWins);
        y.set("pve.rewarded", pveRewarded);
        y.set("tokens.gems", gems);
        y.set("tokens.stars", stars);
        y.set("tokens.star-minutes", starMinutes);
        return y.saveToString();
    }

    public void deserialize(String data) {
        if (data == null || data.isEmpty()) return;
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(data);
        } catch (InvalidConfigurationException e) {
            Bukkit.getLogger().warning("[MineSMP] Defekte Spielerdaten fuer " + uuid + ": " + e.getMessage());
            return;
        }
        xp = y.getDouble("xp");
        killstreak = y.getInt("killstreak");
        bestStreak = y.getInt("best-streak");
        firstJoin = y.getLong("first-join");
        section(y, "skills", (k, s) -> skillXp.put(k, s.getDouble(k)));
        section(y, "keys", (k, s) -> keys.put(k, s.getInt(k)));
        section(y, "homes", (k, s) -> homes.put(k, s.getString(k)));
        section(y, "settings", (k, s) -> settings.put(k, s.getBoolean(k)));
        ignored.addAll(y.getStringList("ignored"));
        section(y, "stats", (k, s) -> stats.put(k, s.getLong(k)));
        section(y, "cooldowns", (k, s) -> cooldowns.put(k, s.getLong(k)));
        achievements.addAll(y.getStringList("achievements"));
        dailyStreak = y.getInt("daily.streak");
        lastDaily = y.getLong("daily.last");
        questDay = y.getString("quests.day", "");
        section(y, "quests.daily", (k, s) -> dailyQuests.put(k, s.getInt(k)));
        dailyDone.addAll(y.getStringList("quests.daily-done"));
        questWeek = y.getString("quests.week", "");
        section(y, "quests.weekly", (k, s) -> weeklyQuests.put(k, s.getInt(k)));
        weeklyDone.addAll(y.getStringList("quests.weekly-done"));
        bpSeason = y.getString("bp.season", "");
        bpXp = y.getInt("bp.xp");
        bpFree.addAll(y.getIntegerList("bp.free"));
        bpPremium.addAll(y.getIntegerList("bp.premium"));
        trail = y.getString("cosmetic.trail", "");
        pet = y.getString("cosmetic.pet", "");
        extraHearts = y.getInt("extra-hearts");
        eventWins = y.getInt("event-wins");
        duelWins = y.getInt("duel.wins");
        duelLosses = y.getInt("duel.losses");
        levelRewards.addAll(y.getIntegerList("level-rewards"));
        savedInventory = y.getString("saved-inventory", "");
        section(y, "kit-layouts", (k, s) -> kitLayouts.put(k, s.getString(k)));
        pveWins = y.getInt("pve.wins");
        pveRewarded = y.getString("pve.rewarded", "");
        gems = y.getLong("tokens.gems");
        stars = y.getLong("tokens.stars");
        starMinutes = y.getInt("tokens.star-minutes");
    }

    private static void section(YamlConfiguration y, String path, java.util.function.BiConsumer<String, ConfigurationSection> c) {
        ConfigurationSection s = y.getConfigurationSection(path);
        if (s == null) return;
        for (String k : s.getKeys(false)) c.accept(k, s);
    }

    // ---- Location-Helfer (als String gespeichert, damit nicht geladene Welten keine Fehler werfen) ----

    public static String locToString(Location l) {
        return l.getWorld().getName() + ";" + round(l.getX()) + ";" + round(l.getY()) + ";" + round(l.getZ()) + ";"
                + round(l.getYaw()) + ";" + round(l.getPitch());
    }

    private static double round(double d) {
        return Math.round(d * 100) / 100.0;
    }

    public static Location locFromString(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(";");
        if (p.length < 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    p.length > 4 ? Float.parseFloat(p[4]) : 0, p.length > 5 ? Float.parseFloat(p[5]) : 0);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
