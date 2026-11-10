package de.minesmp.core.combat;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.TimeUtil;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Kits aus kits.yml (/kit), mit Cooldown und Berechtigung. Werden auch fuer Duelle verwendet. */
public final class KitService {
    public record Kit(String id, String name, Material icon, long cooldownSeconds, String permission, boolean duelOnly,
                      List<ItemStack> items, ItemStack helmet, ItemStack chest, ItemStack legs, ItemStack boots, ItemStack offhand) {}

    private final MineSMP plugin;
    private final Map<String, Kit> kits = new LinkedHashMap<>();

    public KitService(MineSMP plugin) {
        this.plugin = plugin;
        reload();
        plugin.command(new SimpleCommand("kit", "minesmp.kit", "Kits", this::cmd, (s, a) -> a.length == 1 ? new ArrayList<>(kits.keySet()) : List.of(), "kits"));
    }

    public void reload() {
        kits.clear();
        YamlConfiguration y = plugin.yaml("kits.yml");
        ConfigurationSection root = y.getConfigurationSection("kits");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            List<ItemStack> items = new ArrayList<>();
            ConfigurationSection is = s.getConfigurationSection("items");
            if (is != null) for (String k : is.getKeys(false)) items.add(Items.fromConfig(is.getConfigurationSection(k)));
            Material icon = Material.matchMaterial(s.getString("icon", "CHEST"));
            kits.put(id.toLowerCase(Locale.ROOT), new Kit(id, s.getString("name", id), icon == null ? Material.CHEST : icon,
                    s.getLong("cooldown", 86400), s.getString("permission", "minesmp.kit." + id), s.getBoolean("duel-only", false), items,
                    opt(s, "armor.helmet"), opt(s, "armor.chestplate"), opt(s, "armor.leggings"), opt(s, "armor.boots"), opt(s, "offhand")));
        }
    }

    private static ItemStack opt(ConfigurationSection s, String path) {
        return s.isConfigurationSection(path) ? Items.fromConfig(s.getConfigurationSection(path)) : null;
    }

    public Kit get(String id) {
        return id == null ? null : kits.get(id.toLowerCase(Locale.ROOT));
    }

    public List<Kit> duelKits() {
        List<Kit> out = new ArrayList<>();
        for (Kit k : kits.values()) if (k.duelOnly) out.add(k);
        return out;
    }

    /** Legt Kit an (Duell: Inventar vorher geleert). */
    public void equip(Player p, Kit k, boolean replaceArmor) {
        var inv = p.getInventory();
        if (replaceArmor) {
            inv.setHelmet(clone(k.helmet));
            inv.setChestplate(clone(k.chest));
            inv.setLeggings(clone(k.legs));
            inv.setBoots(clone(k.boots));
            inv.setItemInOffHand(clone(k.offhand));
            int[] layout = layout(p, k);
            if (layout != null) {
                for (int i = 0; i < k.items.size(); i++) inv.setItem(layout[i], k.items.get(i).clone());
            } else {
                for (ItemStack it : k.items) inv.addItem(it.clone());
            }
        } else {
            List<ItemStack> all = new ArrayList<>();
            for (ItemStack it : new ItemStack[]{k.helmet, k.chest, k.legs, k.boots, k.offhand}) if (it != null) all.add(it.clone());
            for (ItemStack it : k.items) all.add(it.clone());
            Items.giveOrDrop(p, all.toArray(new ItemStack[0]));
        }
    }

    /** Eigene Anordnung aus dem Kit-Editor (Inventar-Slot je Kit-Item) oder null fuer die Standard-Reihenfolge. */
    public int[] layout(Player p, Kit k) {
        String raw = plugin.data().get(p).kitLayouts.get(k.id.toLowerCase(Locale.ROOT));
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split(",");
        if (parts.length != k.items.size()) return null;
        int[] out = new int[parts.length];
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.parseInt(parts[i].trim());
                if (out[i] < 0 || out[i] > 35 || !seen.add(out[i])) return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private static ItemStack clone(ItemStack i) {
        return i == null ? null : i.clone();
    }

    private void cmd(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        if (a.length == 0) { open(p); return; }
        Kit k = get(a[0]);
        if (k == null || k.duelOnly) { Msg.send(p, "kit.unknown", "{prefix}&cDieses Kit existiert nicht."); return; }
        claim(p, k);
    }

    private void claim(Player p, Kit k) {
        if (!p.hasPermission(k.permission)) { Msg.send(p, "kit.no-perm", "{prefix}&cDieses Kit ist fuer deinen Rang nicht verfuegbar."); return; }
        PlayerData d = plugin.data().get(p);
        long until = d.cooldowns.getOrDefault("kit." + k.id, 0L);
        if (until > System.currentTimeMillis() && !p.hasPermission("minesmp.kit.nocooldown")) {
            Msg.send(p, "cooldown", "{prefix}&cBitte warte noch &f{time}&c.", "time", TimeUtil.format(until - System.currentTimeMillis()));
            return;
        }
        d.cooldowns.put("kit." + k.id, System.currentTimeMillis() + k.cooldownSeconds * 1000);
        d.dirty = true;
        equip(p, k, false);
        Msg.send(p, "kit.received", "{prefix}&aDu hast das Kit &f{kit} &aerhalten.", "kit", k.name);
    }

    private void open(Player p) {
        Menu m = new Menu(3, "&8Kits") {};
        int i = 10;
        PlayerData d = plugin.data().get(p);
        for (Kit k : kits.values()) {
            if (k.duelOnly || i > 16) continue;
            long until = d.cooldowns.getOrDefault("kit." + k.id, 0L) - System.currentTimeMillis();
            boolean perm = p.hasPermission(k.permission);
            m.set(i++, Items.of(k.icon, 1, k.name, List.of(
                    perm ? (until > 0 ? "&cVerfuegbar in " + TimeUtil.format(until) : "&aVerfuegbar - Klicke!") : "&cKein Zugriff",
                    "&7Cooldown: &f" + TimeUtil.format(k.cooldownSeconds * 1000))), e -> { Menu.close(p); claim(p, k); });
        }
        m.fillEmpty();
        m.open(p);
    }
}
