package de.minesmp.core.world;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Custom Items aus custom-items.yml mit Faehigkeiten:
 * HAMMER (3x3 Abbau), TREEFELLER (ganzer Baum), SELLWAND (Truhen-Inhalt verkaufen = Verkaufskiste).
 * Alle Zusatzabbauten laufen ueber Player#breakBlock -> Schutzplugins (WorldGuard, CoreProtect-Logging) greifen.
 * Ausserdem eigene Crafting-Rezepte.
 */
public final class CustomItemService implements Listener {
    private final MineSMP plugin;
    private final NamespacedKey idKey, usesKey;
    private final Map<String, ConfigurationSection> defs = new LinkedHashMap<>();
    private final Map<UUID, BlockFace> lastFace = new HashMap<>();
    private final Set<UUID> breaking = new HashSet<>();
    private final List<NamespacedKey> recipes = new ArrayList<>();

    public CustomItemService(MineSMP plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "custom_item");
        this.usesKey = new NamespacedKey(plugin, "uses");
        YamlConfiguration y = plugin.yaml("custom-items.yml");
        ConfigurationSection items = y.getConfigurationSection("items");
        if (items != null) for (String id : items.getKeys(false)) defs.put(id.toLowerCase(Locale.ROOT), items.getConfigurationSection(id));
        loadRecipes(y.getConfigurationSection("recipes"));
        plugin.listen(this);
        plugin.command(new SimpleCommand("msitem", "minesmp.admin.items", "Custom Items geben", (s, l, a) -> {
            if (a.length < 2) { Msg.send(s, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/msitem <Spieler> <Item> [Anzahl]"); return; }
            Player t = Bukkit.getPlayerExact(a[0]);
            ItemStack it = create(a[1]);
            if (t == null || it == null) { Msg.send(s, "msitem.invalid", "{prefix}&cSpieler oder Item ungueltig. Items: &f{list}", "list", String.join(", ", defs.keySet())); return; }
            int n = 1;
            try { if (a.length > 2) n = Math.max(1, Math.min(64, Integer.parseInt(a[2]))); } catch (NumberFormatException ignored) { }
            for (int i = 0; i < n; i++) Items.giveOrDrop(t, create(a[1]));
            plugin.audit().log(s, "GIVE_ITEM", t.getName() + " " + n + "x " + a[1]);
            Msg.send(s, "msitem.given", "{prefix}&aItem gegeben.");
        }, (s, a) -> a.length == 2 ? new ArrayList<>(defs.keySet()) : a.length == 1 ? null : List.of()));
    }

    public ItemStack create(String id) {
        ConfigurationSection s = id == null ? null : defs.get(id.toLowerCase(Locale.ROOT));
        if (s == null) return null;
        ItemStack it = Items.fromConfig(s);
        int uses = s.getInt("uses", 0);
        it.editMeta(m -> {
            m.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id.toLowerCase(Locale.ROOT));
            if (uses > 0) {
                m.getPersistentDataContainer().set(usesKey, PersistentDataType.INTEGER, uses);
                List<net.kyori.adventure.text.Component> lore = m.hasLore() ? new ArrayList<>(m.lore()) : new ArrayList<>();
                lore.add(Text.item("&7Verbleibende Nutzungen: &f" + uses));
                m.lore(lore);
            }
        });
        return it;
    }

    public String idOf(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    private String ability(ItemStack it) {
        String id = idOf(it);
        if (id == null) return null;
        ConfigurationSection s = defs.get(id);
        return s == null ? null : s.getString("ability", "").toUpperCase(Locale.ROOT);
    }

    private void loadRecipes(ConfigurationSection rs) {
        if (rs == null) return;
        for (String id : rs.getKeys(false)) {
            ConfigurationSection r = rs.getConfigurationSection(id);
            ItemStack result = r.isString("result-custom") ? create(r.getString("result-custom")) : Items.fromConfig(r.getConfigurationSection("result"));
            if (result == null) continue;
            NamespacedKey key = new NamespacedKey(plugin, "recipe_" + id.toLowerCase(Locale.ROOT));
            Bukkit.removeRecipe(key);
            try {
                if (r.isList("shape")) {
                    ShapedRecipe sr = new ShapedRecipe(key, result);
                    sr.shape(r.getStringList("shape").toArray(new String[0]));
                    ConfigurationSection ing = r.getConfigurationSection("ingredients");
                    for (String k : ing.getKeys(false)) sr.setIngredient(k.charAt(0), Material.valueOf(ing.getString(k).toUpperCase(Locale.ROOT)));
                    Bukkit.addRecipe(sr);
                } else {
                    ShapelessRecipe sl = new ShapelessRecipe(key, result);
                    for (String m : r.getStringList("ingredients")) sl.addIngredient(Material.valueOf(m.toUpperCase(Locale.ROOT)));
                    Bukkit.addRecipe(sl);
                }
                recipes.add(key);
            } catch (IllegalArgumentException | NullPointerException ex) {
                plugin.getLogger().warning("Rezept " + id + " ungueltig: " + ex.getMessage());
            }
        }
        if (!recipes.isEmpty()) plugin.getLogger().info(recipes.size() + " eigene Rezepte registriert.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeftClick(PlayerInteractEvent e) {
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) lastFace.put(e.getPlayer().getUniqueId(), e.getBlockFace());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastFace.remove(e.getPlayer().getUniqueId());
        breaking.remove(e.getPlayer().getUniqueId());
    }

    private static boolean breakable(Block b) {
        Material m = b.getType();
        return !m.isAir() && m.getHardness() >= 0 && m.getHardness() < 40 && !(b.getState(false) instanceof Container) && b.getType() != Material.SPAWNER;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (breaking.contains(p.getUniqueId())) return;
        String ab = ability(p.getInventory().getItemInMainHand());
        if (ab == null) return;
        if (ab.equals("HAMMER")) {
            BlockFace face = lastFace.getOrDefault(p.getUniqueId(), BlockFace.UP);
            Block c = e.getBlock();
            List<Block> extra = new ArrayList<>();
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                Block t = switch (face) {
                    case UP, DOWN -> c.getRelative(a, 0, b);
                    case NORTH, SOUTH -> c.getRelative(a, b, 0);
                    default -> c.getRelative(0, a, b);
                };
                if (breakable(t) && t.getType().getHardness() <= c.getType().getHardness() + 1.5f) extra.add(t);
            }
            breakAll(p, extra);
        } else if (ab.equals("TREEFELLER") && Tag.LOGS.isTagged(e.getBlock().getType())
                && (!plugin.getConfig().getBoolean("custom-items.treefeller-requires-sneak", true) || p.isSneaking())) {
            List<Block> logs = new ArrayList<>();
            Deque<Block> q = new ArrayDeque<>();
            Set<Block> seen = new HashSet<>();
            q.add(e.getBlock());
            seen.add(e.getBlock());
            int max = plugin.getConfig().getInt("custom-items.treefeller-max", 96);
            while (!q.isEmpty() && logs.size() < max) {
                Block b = q.poll();
                for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    Block n = b.getRelative(dx, dy, dz);
                    if (seen.add(n) && Tag.LOGS.isTagged(n.getType())) { logs.add(n); q.add(n); }
                }
            }
            breakAll(p, logs);
        }
    }

    private void breakAll(Player p, List<Block> blocks) {
        breaking.add(p.getUniqueId());
        try {
            for (Block b : blocks) {
                if (p.getInventory().getItemInMainHand().getType().isAir()) break; // Werkzeug kaputt
                p.breakBlock(b);
            }
        } finally {
            breaking.remove(p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSellWand(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        ItemStack hand = e.getPlayer().getInventory().getItemInMainHand();
        if (!"SELLWAND".equals(ability(hand))) return;
        if (!(e.getClickedBlock().getState() instanceof Container c)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!plugin.cooldowns().tryUse(p.getUniqueId(), "sellwand", 1000)) return;
        double total = plugin.shop().sellInventory(p, c.getInventory(), false);
        if (total <= 0) { Msg.send(p, "sell.nothing", "{prefix}&cDu hast nichts Verkaufbares dabei."); return; }
        Integer uses = hand.getItemMeta().getPersistentDataContainer().get(usesKey, PersistentDataType.INTEGER);
        if (uses != null) {
            if (uses <= 1) {
                hand.setAmount(hand.getAmount() - 1);
                Msg.send(p, "sellwand.broken", "{prefix}&7Dein Verkaufsstab ist aufgebraucht.");
            } else {
                hand.editMeta(m -> {
                    m.getPersistentDataContainer().set(usesKey, PersistentDataType.INTEGER, uses - 1);
                    List<net.kyori.adventure.text.Component> lore = m.hasLore() ? new ArrayList<>(m.lore()) : new ArrayList<>();
                    if (!lore.isEmpty()) lore.set(lore.size() - 1, Text.item("&7Verbleibende Nutzungen: &f" + (uses - 1)));
                    m.lore(lore);
                });
            }
        }
    }
}
