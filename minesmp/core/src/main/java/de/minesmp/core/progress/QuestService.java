package de.minesmp.core.progress;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.utility.RewardService;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Taegliche und woechentliche Quests aus quests.yml. Zuteilung zufaellig, aber pro Spieler+Tag stabil. */
public final class QuestService implements Listener {
    public record Quest(String id, String name, String type, List<String> targets, int amount, Material icon, ConfigurationSection reward) {}

    private final MineSMP plugin;
    private final java.util.Map<String, Quest> daily = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Quest> weekly = new java.util.LinkedHashMap<>();
    private int dailyCount, weeklyCount;

    public QuestService(MineSMP plugin) {
        this.plugin = plugin;
        YamlConfiguration y = plugin.yaml("quests.yml");
        dailyCount = y.getInt("daily.count", 3);
        weeklyCount = y.getInt("weekly.count", 3);
        load(y.getConfigurationSection("daily.pool"), daily);
        load(y.getConfigurationSection("weekly.pool"), weekly);
        plugin.listen(this);
        plugin.command(new SimpleCommand("quests", "minesmp.quests", "Deine Quests", (s, l, a) -> { if (s instanceof Player p) open(p); }, "quest", "aufgaben"));
    }

    private void load(ConfigurationSection s, java.util.Map<String, Quest> into) {
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection q = s.getConfigurationSection(id);
            List<String> targets = new ArrayList<>();
            if (q.isList("target")) q.getStringList("target").forEach(t -> targets.add(t.toUpperCase(Locale.ROOT)));
            else if (q.isString("target")) targets.add(q.getString("target").toUpperCase(Locale.ROOT));
            Material icon = Material.matchMaterial(q.getString("icon", "PAPER"));
            into.put(id, new Quest(id, q.getString("name", id), q.getString("type", "BREAK").toUpperCase(Locale.ROOT), targets,
                    Math.max(1, q.getInt("amount", 1)), icon == null ? Material.PAPER : icon, q.getConfigurationSection("reward")));
        }
    }

    private static String week() {
        LocalDate d = LocalDate.now(RewardService.ZONE);
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    /** Stellt sicher, dass der Spieler aktuelle Quests hat. */
    public void ensure(Player p) {
        PlayerData d = plugin.data().get(p);
        String today = RewardService.today();
        if (!today.equals(d.questDay)) {
            d.questDay = today;
            d.dailyQuests.clear();
            d.dailyDone.clear();
            for (String id : pick(daily, dailyCount, p.getUniqueId().hashCode() ^ today.hashCode())) d.dailyQuests.put(id, 0);
            d.dirty = true;
        }
        String w = week();
        if (!w.equals(d.questWeek)) {
            d.questWeek = w;
            d.weeklyQuests.clear();
            d.weeklyDone.clear();
            for (String id : pick(weekly, weeklyCount, p.getUniqueId().hashCode() ^ w.hashCode())) d.weeklyQuests.put(id, 0);
            d.dirty = true;
        }
    }

    private static List<String> pick(java.util.Map<String, Quest> pool, int n, long seed) {
        List<String> ids = new ArrayList<>(pool.keySet());
        Collections.shuffle(ids, new Random(seed));
        return ids.subList(0, Math.min(n, ids.size()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        ensure(e.getPlayer());
    }

    private static boolean matches(Quest q, String target) {
        if (q.targets.isEmpty()) return true;
        if (target == null) return false;
        for (String t : q.targets) {
            if (t.equals(target)) return true;
            if (t.startsWith("*") && target.endsWith(t.substring(1))) return true;
            if (t.endsWith("*") && target.startsWith(t.substring(0, t.length() - 1))) return true;
        }
        return false;
    }

    /** Fortschritt melden, z.B. progress(p, "BREAK", "DIAMOND_ORE", 1). */
    public void progress(Player p, String type, String target, int amount) {
        if (amount <= 0) return;
        PlayerData d = plugin.data().get(p);
        ensure(p);
        step(p, d, d.dailyQuests, d.dailyDone, daily, type, target, amount, false);
        step(p, d, d.weeklyQuests, d.weeklyDone, weekly, type, target, amount, true);
    }

    private void step(Player p, PlayerData d, Map<String, Integer> active, java.util.Set<String> done, java.util.Map<String, Quest> pool,
                      String type, String target, int amount, boolean isWeekly) {
        for (var e : active.entrySet()) {
            if (done.contains(e.getKey())) continue;
            Quest q = pool.get(e.getKey());
            if (q == null || !q.type.equals(type) || !matches(q, target)) continue;
            int now = Math.min(q.amount, e.getValue() + amount);
            e.setValue(now);
            d.dirty = true;
            if (now >= q.amount) {
                done.add(q.id);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
                Msg.send(p, "quest.done", "{prefix}&a&lQUEST ABGESCHLOSSEN! &f{name} &8({kind})", "name", q.name, "kind", isWeekly ? "woechentlich" : "taeglich");
                plugin.rewards().give(p, q.reward, q.name);
                d.addStat("quests_done", 1);
                if (plugin.clans().of(p) != null) plugin.clans().addXp(plugin.clans().of(p), plugin.getConfig().getDouble("clans.xp.quest", 20));
            }
        }
    }

    public void open(Player p) {
        ensure(p);
        PlayerData d = plugin.data().get(p);
        Menu m = new Menu(4, "&8Deine Quests") {};
        m.set(4, Items.of(Material.SUNFLOWER, 1, "&e&lTaegliche Quests", List.of("&7Neue Quests jeden Tag um 0 Uhr")));
        int slot = 10;
        for (var e : d.dailyQuests.entrySet()) item(m, slot++, daily.get(e.getKey()), e.getValue(), d.dailyDone.contains(e.getKey()));
        m.set(22, Items.of(Material.CLOCK, 1, "&b&lWoechentliche Quests", List.of("&7Neue Quests jeden Montag")));
        slot = 28;
        for (var e : d.weeklyQuests.entrySet()) item(m, slot++, weekly.get(e.getKey()), e.getValue(), d.weeklyDone.contains(e.getKey()));
        m.fillEmpty();
        m.open(p);
    }

    private void item(Menu m, int slot, Quest q, int progress, boolean done) {
        if (q == null) return;
        List<String> lore = new ArrayList<>();
        lore.add("&7Fortschritt: &f" + progress + "&7/&f" + q.amount);
        lore.add(Text.progressBar(progress / (double) q.amount, 20, "&a", "&8"));
        if (q.reward != null) {
            lore.add("");
            lore.add("&7Belohnung:");
            if (q.reward.getDouble("money") > 0) lore.add(" &8» &a" + plugin.eco().format(q.reward.getDouble("money")));
            if (q.reward.getInt("bp-xp") > 0) lore.add(" &8» &d" + q.reward.getInt("bp-xp") + " Battlepass-XP");
            ConfigurationSection k = q.reward.getConfigurationSection("keys");
            if (k != null) for (String key : k.getKeys(false)) lore.add(" &8» &e" + k.getInt(key) + "x " + key + "-Schluessel");
        }
        lore.add("");
        lore.add(done ? "&a✔ Abgeschlossen" : "&7In Arbeit...");
        m.set(slot, Items.of(done ? Material.LIME_DYE : q.icon, 1, (done ? "&a" : "&f") + q.name, lore));
    }
}
