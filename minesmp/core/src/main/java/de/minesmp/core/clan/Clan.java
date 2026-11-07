package de.minesmp.core.clan;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Clan {
    public enum Rank {
        MEMBER("&7Mitglied"), MODERATOR("&bModerator"), CO_LEADER("&3Co-Leiter"), LEADER("&6Leiter");
        public final String display;
        Rank(String d) { this.display = d; }
        public boolean atLeast(Rank r) { return ordinal() >= r.ordinal(); }
    }

    public final String id;
    public String name, tag;
    public final Map<UUID, Rank> members = new LinkedHashMap<>();
    public final Map<UUID, String> names = new LinkedHashMap<>();
    public int level = 1;
    public double xp;
    public double bank;
    public String home = "";
    public final Set<String> allies = new HashSet<>();
    public final Set<String> rivals = new HashSet<>();
    public long created;
    public int kills, warsWon, warsLost;
    public int seasonWins, seasonDraws, seasonLosses;
    public boolean friendlyFire;

    public Clan(String id, String name, String tag) {
        this.id = id;
        this.name = name;
        this.tag = tag;
    }

    public UUID leader() {
        for (var e : members.entrySet()) if (e.getValue() == Rank.LEADER) return e.getKey();
        return members.keySet().stream().findFirst().orElse(null);
    }

    /** Saison-Punkte: Sieg 3, Unentschieden 1. */
    public int seasonPoints() {
        return seasonWins * 3 + seasonDraws;
    }

    public Rank rank(UUID id) {
        return members.get(id);
    }

    public String serialize() {
        YamlConfiguration y = new YamlConfiguration();
        members.forEach((k, v) -> {
            y.set("members." + k + ".rank", v.name());
            y.set("members." + k + ".name", names.getOrDefault(k, "?"));
        });
        y.set("level", level);
        y.set("xp", xp);
        y.set("bank", bank);
        y.set("home", home);
        y.set("allies", allies.stream().toList());
        y.set("rivals", rivals.stream().toList());
        y.set("created", created);
        y.set("kills", kills);
        y.set("wars-won", warsWon);
        y.set("wars-lost", warsLost);
        y.set("season.wins", seasonWins);
        y.set("season.draws", seasonDraws);
        y.set("season.losses", seasonLosses);
        y.set("friendly-fire", friendlyFire);
        return y.saveToString();
    }

    public void deserialize(String s) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(s == null ? "" : s);
        } catch (InvalidConfigurationException e) {
            return;
        }
        ConfigurationSection m = y.getConfigurationSection("members");
        if (m != null) {
            for (String k : m.getKeys(false)) {
                try {
                    UUID u = UUID.fromString(k);
                    members.put(u, Rank.valueOf(m.getString(k + ".rank", "MEMBER")));
                    names.put(u, m.getString(k + ".name", "?"));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        level = Math.max(1, y.getInt("level", 1));
        xp = y.getDouble("xp");
        bank = y.getDouble("bank");
        home = y.getString("home", "");
        allies.addAll(y.getStringList("allies"));
        rivals.addAll(y.getStringList("rivals"));
        created = y.getLong("created");
        kills = y.getInt("kills");
        warsWon = y.getInt("wars-won");
        warsLost = y.getInt("wars-lost");
        seasonWins = y.getInt("season.wins");
        seasonDraws = y.getInt("season.draws");
        seasonLosses = y.getInt("season.losses");
        friendlyFire = y.getBoolean("friendly-fire");
    }
}
