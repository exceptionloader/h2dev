package de.minesmp.core.progress;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Season-Battlepass mit kostenloser und Premium-Reihe (Premium = minesmp.battlepass.premium). */
public final class BattlePassService {
    private final MineSMP plugin;
    private YamlConfiguration cfg;
    private String season;
    private int xpPerTier, maxTier;

    public BattlePassService(MineSMP plugin) {
        this.plugin = plugin;
        cfg = plugin.yaml("battlepass.yml");
        season = cfg.getString("season", "S1");
        xpPerTier = Math.max(1, cfg.getInt("xp-per-tier", 500));
        ConfigurationSection tiers = cfg.getConfigurationSection("tiers");
        maxTier = 0;
        if (tiers != null) for (String k : tiers.getKeys(false)) try { maxTier = Math.max(maxTier, Integer.parseInt(k)); } catch (NumberFormatException ignored) { }
        plugin.command(new SimpleCommand("battlepass", "minesmp.battlepass", "Battlepass", (s, l, a) -> { if (s instanceof Player p) open(p, 0); }, "bp", "pass"));
    }

    private PlayerData data(Player p) {
        PlayerData d = plugin.data().get(p);
        if (!season.equals(d.bpSeason)) {
            d.bpSeason = season;
            d.bpXp = 0;
            d.bpFree.clear();
            d.bpPremium.clear();
            d.dirty = true;
        }
        return d;
    }

    public int tier(Player p) {
        return Math.min(maxTier, data(p).bpXp / xpPerTier);
    }

    public void addXp(Player p, int xp) {
        if (xp <= 0 || maxTier == 0) return;
        PlayerData d = data(p);
        int before = tier(p);
        d.bpXp += xp;
        d.dirty = true;
        int after = tier(p);
        if (after > before) {
            Msg.send(p, "bp.tier", "{prefix}&d&lBATTLEPASS &7Stufe &d{tier} &7erreicht! &f/battlepass", "tier", String.valueOf(after));
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1, 1.2f);
        }
    }

    public void open(Player p, int page) {
        PlayerData d = data(p);
        int tier = tier(p);
        boolean premium = p.hasPermission("minesmp.battlepass.premium");
        Menu m = new Menu(6, "&8Battlepass " + Text.strip(cfg.getString("name", season)) + " &7(" + (page + 1) + ")") {};
        m.set(4, Items.of(Material.NETHER_STAR, 1, "&d&lBattlepass " + cfg.getString("name", season), List.of(
                "&7Stufe: &d" + tier + "&7/&d" + maxTier,
                "&7XP: &f" + (d.bpXp % xpPerTier) + "&7/&f" + xpPerTier,
                Text.progressBar((d.bpXp % xpPerTier) / (double) xpPerTier, 20, "&d", "&8"),
                "&7Premium: " + (premium ? "&aaktiv" : "&cnicht aktiv &8(Shop/Rang)"),
                "", "&7XP durch Skills, Quests und Events")));
        int per = 7;
        for (int i = 0; i < per; i++) {
            int t = page * per + i + 1;
            if (t > maxTier) break;
            ConfigurationSection free = cfg.getConfigurationSection("tiers." + t + ".free");
            ConfigurationSection prem = cfg.getConfigurationSection("tiers." + t + ".premium");
            m.set(19 + i, Items.of(t <= tier ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE, 1, "&fStufe " + t, List.of()));
            m.set(10 + i, rewardItem(free, "&aKostenlos", t <= tier, d.bpFree.contains(t)), e -> claim(p, t, false, page));
            m.set(28 + i, rewardItem(prem, "&6Premium", t <= tier && premium, d.bpPremium.contains(t)), e -> claim(p, t, true, page));
        }
        if (page > 0) m.set(45, Items.of(Material.SPECTRAL_ARROW, "&bVorherige Seite"), e -> open(p, page - 1));
        if ((page + 1) * per < maxTier) m.set(53, Items.of(Material.SPECTRAL_ARROW, "&bNaechste Seite"), e -> open(p, page + 1));
        m.fillEmpty();
        m.open(p);
    }

    private org.bukkit.inventory.ItemStack rewardItem(ConfigurationSection r, String kind, boolean unlocked, boolean claimed) {
        if (r == null) return Items.of(Material.BARRIER, kind + " &8- &7keine Belohnung");
        Material icon = Material.matchMaterial(r.getString("icon", "CHEST"));
        List<String> lore = new ArrayList<>();
        lore.add("&7" + r.getString("display", "Belohnung"));
        lore.add("");
        lore.add(claimed ? "&a✔ Abgeholt" : unlocked ? "&eKlicke zum Abholen" : "&cGesperrt");
        var it = Items.of(icon == null ? Material.CHEST : icon, 1, kind, lore);
        return unlocked && !claimed ? Items.glow(it) : it;
    }

    private void claim(Player p, int t, boolean premium, int page) {
        PlayerData d = data(p);
        if (t > tier(p)) return;
        if (premium && !p.hasPermission("minesmp.battlepass.premium")) {
            Msg.send(p, "bp.no-premium", "{prefix}&cDu brauchst den Premium-Battlepass.");
            return;
        }
        var set = premium ? d.bpPremium : d.bpFree;
        if (!set.add(t)) return;
        d.dirty = true;
        plugin.rewards().give(p, cfg.getConfigurationSection("tiers." + t + (premium ? ".premium" : ".free")), "Battlepass Stufe " + t);
        open(p, page);
    }
}
