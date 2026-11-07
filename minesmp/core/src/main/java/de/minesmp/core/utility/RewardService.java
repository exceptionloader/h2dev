package de.minesmp.core.utility;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Zentrale Belohnungsvergabe (Geld, Schluessel, Items, Befehle, Battlepass-XP, Clan-XP)
 * sowie /daily (taegliche Belohnung mit Streak) und /rewards (Uebersicht).
 * Belohnungs-Format (YAML):
 *   money: 500
 *   keys: {rare: 1}
 *   bp-xp: 50
 *   clan-xp: 10
 *   items: [ {material: DIAMOND, amount: 3} ]
 *   custom-items: [hammer]
 *   gems: 10 / stars: 5
 *   spawners: {zombie: 1}
 *   books: ["vampir:2", "zufall:1"]
 *   commands: ["lp user {player} permission set xyz"]
 */
public final class RewardService {
    private final MineSMP plugin;
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    public RewardService(MineSMP plugin) {
        this.plugin = plugin;
        plugin.command(new SimpleCommand("daily", "minesmp.daily", "Taegliche Belohnung", (s, l, a) -> { if (s instanceof Player p) daily(p); }, "taeglich", "tages"));
        plugin.command(new SimpleCommand("rewards", "minesmp.rewards", "Belohnungen", (s, l, a) -> { if (s instanceof Player p) openRewards(p); }, "belohnungen"));
    }

    /** Vergibt eine Belohnung und gibt eine lesbare Zusammenfassung zurueck. */
    public String give(Player p, ConfigurationSection r, String reason) {
        if (r == null) return "";
        List<String> parts = new ArrayList<>();
        double money = r.getDouble("money");
        if (money > 0) {
            plugin.eco().deposit(p, money, "REWARD", reason);
            parts.add("&a" + plugin.eco().format(money));
        }
        ConfigurationSection keys = r.getConfigurationSection("keys");
        if (keys != null) {
            for (String k : keys.getKeys(false)) {
                int n = keys.getInt(k);
                plugin.crates().giveKeys(p.getUniqueId(), k, n);
                parts.add("&e" + n + "x " + plugin.crates().displayName(k) + "-Schluessel");
            }
        }
        long gems = r.getLong("gems");
        if (gems > 0 && plugin.tokens() != null) { plugin.tokens().give(p.getUniqueId(), de.minesmp.core.economy.TokenService.Kind.GEMS, gems, reason); parts.add("&d" + gems + " Gems"); }
        long stars = r.getLong("stars");
        if (stars > 0 && plugin.tokens() != null) { plugin.tokens().give(p.getUniqueId(), de.minesmp.core.economy.TokenService.Kind.STARS, stars, reason); parts.add("&e" + stars + " Sterne"); }
        ConfigurationSection sp = r.getConfigurationSection("spawners");
        if (sp != null && plugin.spawners() != null) {
            for (String type : sp.getKeys(false)) {
                ItemStack it = plugin.spawners().item(type, Math.max(1, sp.getInt(type)));
                if (it != null) { Items.giveOrDrop(p, it); parts.add("&b" + it.getAmount() + "x " + Items.name(it)); }
            }
        }
        for (String book : r.getStringList("books")) {
            ItemStack it = plugin.enchants() == null ? null : plugin.enchants().bookFromSpec(book);
            if (it != null) { Items.giveOrDrop(p, it); parts.add(Items.name(it)); }
        }
        int bp = r.getInt("bp-xp");
        if (bp > 0) { plugin.battlepass().addXp(p, bp); parts.add("&d" + bp + " Battlepass-XP"); }
        double cxp = r.getDouble("clan-xp");
        if (cxp > 0 && plugin.clans().of(p) != null) { plugin.clans().addXp(plugin.clans().of(p), cxp); parts.add("&b" + (int) cxp + " Clan-XP"); }
        List<ItemStack> items = new ArrayList<>();
        for (Map<?, ?> m : r.getMapList("items")) {
            org.bukkit.configuration.MemoryConfiguration mc = new org.bukkit.configuration.MemoryConfiguration();
            m.forEach((k, v) -> mc.set(String.valueOf(k), v));
            ItemStack it = Items.fromConfig(mc);
            items.add(it);
            parts.add("&f" + it.getAmount() + "x " + Items.name(it));
        }
        for (String ci : r.getStringList("custom-items")) {
            String[] cs = ci.split(":");
            int n = 1;
            try { if (cs.length > 1) n = Math.max(1, Math.min(64, Integer.parseInt(cs[1]))); } catch (NumberFormatException ignored) { }
            ItemStack it = plugin.customItems().create(cs[0]);
            if (it != null) { it.setAmount(Math.min(n, it.getMaxStackSize())); items.add(it); parts.add("&f" + (n > 1 ? n + "x " : "") + Items.name(it)); }
        }
        if (!items.isEmpty()) Items.giveOrDrop(p, items.toArray(new ItemStack[0]));
        for (String cmd : r.getStringList("commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()).replace("{uuid}", p.getUniqueId().toString()));
        }
        if (r.isString("display")) parts.add(r.getString("display"));
        String summary = String.join("&7, ", parts);
        if (!summary.isEmpty()) Msg.send(p, "reward.received", "{prefix}&7Belohnung ({reason}&7): {summary}", "reason", reason, "summary", summary);
        return summary;
    }

    public static String today() {
        return LocalDate.now(ZONE).toString();
    }

    private void daily(Player p) {
        PlayerData d = plugin.data().get(p);
        LocalDate now = LocalDate.now(ZONE);
        LocalDate last = d.lastDaily == 0 ? null : java.time.Instant.ofEpochMilli(d.lastDaily).atZone(ZONE).toLocalDate();
        if (last != null && last.equals(now)) {
            long next = now.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli() - System.currentTimeMillis();
            Msg.send(p, "daily.claimed", "{prefix}&cDu hast deine taegliche Belohnung schon abgeholt. Naechste in &f{time}&c.", "time", TimeUtil.format(next));
            return;
        }
        d.dailyStreak = last != null && last.plusDays(1).equals(now) ? d.dailyStreak + 1 : 1;
        d.lastDaily = System.currentTimeMillis();
        d.dirty = true;
        int maxDay = plugin.getConfig().getInt("daily.cycle-days", 7);
        int day = ((d.dailyStreak - 1) % maxDay) + 1;
        ConfigurationSection r = plugin.getConfig().getConfigurationSection("daily.rewards." + day);
        double base = plugin.getConfig().getDouble("daily.base-money", 250);
        double money = base + plugin.getConfig().getDouble("daily.per-streak-day", 50) * Math.min(d.dailyStreak, 30);
        plugin.eco().deposit(p, money, "DAILY", "Tag " + d.dailyStreak);
        Msg.send(p, "daily.received", "{prefix}&aTaegliche Belohnung abgeholt! &7Streak: &f{streak} Tage &8| &a+{money}",
                "streak", String.valueOf(d.dailyStreak), "money", plugin.eco().format(money));
        if (r != null) give(p, r, "Daily Tag " + day);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.6f);
    }

    private void openRewards(Player p) {
        PlayerData d = plugin.data().get(p);
        boolean dailyReady = d.lastDaily == 0 || !java.time.Instant.ofEpochMilli(d.lastDaily).atZone(ZONE).toLocalDate().equals(LocalDate.now(ZONE));
        Menu m = new Menu(3, "&8Belohnungen") {};
        m.set(10, Items.of(Material.SUNFLOWER, 1, "&eTaegliche Belohnung", List.of(dailyReady ? "&aBereit! Klicke zum Abholen" : "&cHeute schon abgeholt",
                "&7Streak: &f" + d.dailyStreak + " Tage")), e -> { Menu.close(p); daily(p); });
        m.set(12, Items.of(Material.WRITABLE_BOOK, 1, "&bQuests", List.of("&7Taegliche & woechentliche Aufgaben")), e -> plugin.quests().open(p));
        m.set(14, Items.of(Material.NETHER_STAR, 1, "&dBattlepass", List.of("&7Season-Belohnungen")), e -> plugin.battlepass().open(p, 0));
        m.set(16, Items.of(Material.EMERALD, 1, "&aVoten", List.of("&7Vote fuer MineSMP und erhalte", "&7Vote-Schluessel! &f/vote")), e -> { Menu.close(p); p.performCommand("vote"); });
        m.set(22, Items.of(Material.TRIPWIRE_HOOK, 1, "&6Deine Schluessel", plugin.crates().keyLore(p)));
        m.fillEmpty();
        m.open(p);
    }

    public String fmt(double v) { return Text.money(v); }
}
