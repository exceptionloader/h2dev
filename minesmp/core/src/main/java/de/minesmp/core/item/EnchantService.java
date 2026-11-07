package de.minesmp.core.item;

import de.minesmp.core.MineSMP;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Eigene Verzauberungen (enchants.yml). Gespeichert im Item (PersistentData "ce"), angezeigt in den ersten Lore-Zeilen.
 * Vergabe ueber Verzauberungsbuecher: Buch auf das Item im Inventar ziehen.
 * Effekte: Waffen (vampir, donner, frost, gift, henker, wither), Werkzeuge (telekinese, autoschmelze, erfahrung, ader, bohrer),
 * Ruestung (tempo, sprung, nachtsicht, feuerfest, panzer).
 */
public final class EnchantService implements Listener {
    public record Def(String id, String name, int max, List<String> targets, List<String> desc, int crystalCost) {}

    private final MineSMP plugin;
    private final NamespacedKey ceKey, linesKey, bookKey, arrowKey;
    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Map<Material, Material> smelt = new EnumMap<>(Material.class);
    private YamlConfiguration cfg;

    public EnchantService(MineSMP plugin) {
        this.plugin = plugin;
        ceKey = new NamespacedKey(plugin, "ce");
        linesKey = new NamespacedKey(plugin, "ce_lines");
        bookKey = new NamespacedKey(plugin, "ce_book");
        arrowKey = new NamespacedKey(plugin, "ce_arrow");
        reload();
        plugin.listen(this);
        plugin.command(new SimpleCommand("verzauberungen", "minesmp.ce", "Eigene Verzauberungen", (s, l, a) -> {
            if (a.length >= 4 && a[0].equalsIgnoreCase("give") && s.hasPermission("minesmp.admin.ce")) {
                Player t = Bukkit.getPlayerExact(a[1]);
                ItemStack book = book(a[2], parseInt(a[3]));
                if (t == null || book == null) { Msg.send(s, "ce.invalid", "{prefix}&cSpieler oder Verzauberung ungueltig. &f{list}", "list", String.join(", ", defs.keySet())); return; }
                Items.giveOrDrop(t, book);
                plugin.audit().log(s, "GIVE_CE", t.getName() + " " + a[2] + " " + a[3]);
                Msg.send(s, "ce.given", "{prefix}&aBuch gegeben.");
                return;
            }
            if (a.length >= 3 && a[0].equalsIgnoreCase("add") && s.hasPermission("minesmp.admin.ce") && s instanceof Player p) {
                Def d = defs.get(a[1].toLowerCase(Locale.ROOT));
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (d == null || hand.getType().isAir()) { Msg.send(s, "ce.invalid", "{prefix}&cSpieler oder Verzauberung ungueltig. &f{list}", "list", String.join(", ", defs.keySet())); return; }
                apply(hand, d.id, Math.min(d.max, Math.max(1, parseInt(a[2]))));
                Msg.send(s, "ce.added", "{prefix}&aVerzauberung hinzugefuegt.");
                return;
            }
            if (s instanceof Player p) open(p);
        }, (s, a) -> {
            if (a.length == 1) return s.hasPermission("minesmp.admin.ce") ? List.of("give", "add") : List.of();
            if (a.length == 2 && a[0].equalsIgnoreCase("give")) return null;
            if ((a.length == 3 && a[0].equalsIgnoreCase("give")) || (a.length == 2 && a[0].equalsIgnoreCase("add"))) return new ArrayList<>(defs.keySet());
            return List.of();
        }, "ce", "enchants", "verzaubern"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::armorTick, 40, 40);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 1; }
    }

    public void reload() {
        cfg = plugin.yaml("enchants.yml");
        defs.clear();
        ConfigurationSection es = cfg.getConfigurationSection("enchants");
        if (es != null) for (String id : es.getKeys(false)) {
            ConfigurationSection e = es.getConfigurationSection(id);
            if (e == null || !e.getBoolean("enabled", true)) continue;
            defs.put(id.toLowerCase(Locale.ROOT), new Def(id.toLowerCase(Locale.ROOT), e.getString("name", Text.capitalize(id)), Math.max(1, e.getInt("max-level", 1)),
                    e.getStringList("targets"), e.getStringList("description"), e.getInt("crystal-cost", 8)));
        }
        smelt.clear();
        ConfigurationSection sm = cfg.getConfigurationSection("autoschmelze");
        if (sm != null) for (String k : sm.getKeys(false)) {
            Material a = Material.matchMaterial(k), b = Material.matchMaterial(sm.getString(k, ""));
            if (a != null && b != null) smelt.put(a, b);
        }
    }

    public Map<String, Def> defs() { return defs; }

    // ---------------- Daten am Item ----------------

    public Map<String, Integer> get(ItemStack it) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return out;
        String raw = it.getItemMeta().getPersistentDataContainer().get(ceKey, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            String[] kv = part.split(":");
            if (kv.length == 2) out.put(kv[0], parseInt(kv[1]));
        }
        return out;
    }

    public int level(ItemStack it, String id) {
        return get(it).getOrDefault(id, 0);
    }

    public static String roman(int n) {
        return switch (n) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V"; default -> String.valueOf(n); };
    }

    private String display(Def d, int lvl) {
        return d.name + (d.max > 1 ? " " + roman(lvl) : "");
    }

    public void apply(ItemStack it, String id, int lvl) {
        Map<String, Integer> cur = get(it);
        cur.put(id, lvl);
        write(it, cur);
    }

    private void write(ItemStack it, Map<String, Integer> ces) {
        it.editMeta(m -> {
            PersistentDataContainer pdc = m.getPersistentDataContainer();
            StringBuilder sb = new StringBuilder();
            ces.forEach((k, v) -> { if (sb.length() > 0) sb.append(','); sb.append(k).append(':').append(v); });
            pdc.set(ceKey, PersistentDataType.STRING, sb.toString());
            List<Component> lore = m.hasLore() ? new ArrayList<>(m.lore()) : new ArrayList<>();
            int old = pdc.getOrDefault(linesKey, PersistentDataType.INTEGER, 0);
            for (int i = 0; i < old && !lore.isEmpty(); i++) lore.remove(0);
            List<Component> add = new ArrayList<>();
            ces.forEach((k, v) -> { Def d = defs.get(k); if (d != null) add.add(Text.item(display(d, v))); });
            lore.addAll(0, add);
            pdc.set(linesKey, PersistentDataType.INTEGER, add.size());
            m.lore(lore);
            m.setEnchantmentGlintOverride(true);
        });
    }

    public static boolean fits(Material m, List<String> targets) {
        String n = m.name();
        for (String t : targets) {
            switch (t.toUpperCase(Locale.ROOT)) {
                case "SWORD" -> { if (n.endsWith("_SWORD")) return true; }
                case "AXE" -> { if (n.endsWith("_AXE")) return true; }
                case "PICKAXE" -> { if (n.endsWith("_PICKAXE")) return true; }
                case "SHOVEL" -> { if (n.endsWith("_SHOVEL")) return true; }
                case "HOE" -> { if (n.endsWith("_HOE")) return true; }
                case "TOOL" -> { if (n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_AXE") || n.endsWith("_HOE")) return true; }
                case "BOW" -> { if (m == Material.BOW || m == Material.CROSSBOW) return true; }
                case "TRIDENT" -> { if (m == Material.TRIDENT) return true; }
                case "MACE" -> { if (m == Material.MACE) return true; }
                case "HELMET" -> { if (n.endsWith("_HELMET") || m == Material.TURTLE_HELMET) return true; }
                case "CHESTPLATE" -> { if (n.endsWith("_CHESTPLATE")) return true; }
                case "LEGGINGS" -> { if (n.endsWith("_LEGGINGS")) return true; }
                case "BOOTS" -> { if (n.endsWith("_BOOTS")) return true; }
                case "ARMOR" -> { if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")) return true; }
                default -> { if (n.equals(t.toUpperCase(Locale.ROOT))) return true; }
            }
        }
        return false;
    }

    // ---------------- Buecher ----------------

    public ItemStack book(String id, int lvl) {
        if (id == null) return null;
        if (id.equalsIgnoreCase("zufall") || id.equalsIgnoreCase("random")) {
            List<Def> all = new ArrayList<>(defs.values());
            if (all.isEmpty()) return null;
            Def d = all.get(ThreadLocalRandom.current().nextInt(all.size()));
            return book(d.id, Math.min(d.max, Math.max(1, lvl)));
        }
        Def d = defs.get(id.toLowerCase(Locale.ROOT));
        if (d == null) return null;
        int l = Math.min(d.max, Math.max(1, lvl));
        List<String> lore = new ArrayList<>();
        for (String s : d.desc) lore.add("&7" + s);
        lore.add("&8");
        lore.add("&7Fuer: &f" + String.join(", ", d.targets).toLowerCase(Locale.ROOT));
        lore.add("&eZiehe das Buch im Inventar auf ein Item.");
        ItemStack it = Items.of(Material.ENCHANTED_BOOK, 1, "&d&lBuch: &r" + display(d, l), lore);
        it.editMeta(m -> m.getPersistentDataContainer().set(bookKey, PersistentDataType.STRING, d.id + ":" + l));
        return it;
    }

    /** "vampir:2" oder "zufall:1" */
    public ItemStack bookFromSpec(String spec) {
        String[] p = spec.split(":");
        return book(p[0], p.length > 1 ? parseInt(p[1]) : 1);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onApplyBook(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getClickedInventory() instanceof PlayerInventory)) return;
        if (e.getClick() != ClickType.LEFT && e.getClick() != ClickType.RIGHT) return;
        ItemStack cursor = e.getCursor();
        ItemStack target = e.getCurrentItem();
        if (cursor == null || cursor.getType() != Material.ENCHANTED_BOOK || !cursor.hasItemMeta() || target == null || target.getType().isAir()) return;
        String spec = cursor.getItemMeta().getPersistentDataContainer().get(bookKey, PersistentDataType.STRING);
        if (spec == null) return;
        String[] sp = spec.split(":");
        Def d = defs.get(sp[0]);
        if (d == null) return;
        e.setCancelled(true);
        if (target.getAmount() != 1 || !fits(target.getType(), d.targets)) { Msg.send(p, "ce.nofit", "{prefix}&cDieses Buch passt nicht auf dieses Item."); return; }
        int lvl = parseInt(sp.length > 1 ? sp[1] : "1");
        int cur = level(target, d.id);
        int max = plugin.getConfig().getInt("custom-enchants.max-per-item", 4);
        if (cur == 0 && get(target).size() >= max) { Msg.send(p, "ce.full", "{prefix}&cMehr als {max} Verzauberungen passen nicht auf ein Item.", "max", String.valueOf(max)); return; }
        int result = cur == lvl && lvl < d.max ? lvl + 1 : Math.max(cur, lvl);
        if (result == cur) { Msg.send(p, "ce.already", "{prefix}&cDas Item hat schon diese Stufe."); return; }
        apply(target, d.id, result);
        e.setCurrentItem(target);
        cursor.setAmount(cursor.getAmount() - 1);
        e.getView().setCursor(cursor.getAmount() <= 0 ? null : cursor);
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1, 1.2f);
        Msg.send(p, "ce.applied", "{prefix}&aVerzaubert: &r{name}", "name", display(d, result));
    }

    // ---------------- Menue (/ce): Uebersicht + Kauf mit Kristallen ----------------

    private void open(Player p) {
        Menu m = new Menu(5, "&8Verzauberer") {};
        int slot = 10;
        int crystals = plugin.customItems().count(p, "kristall");
        for (Def d : defs.values()) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= 35) break;
            List<String> lore = new ArrayList<>();
            for (String s : d.desc) lore.add("&7" + s);
            lore.add("&8");
            lore.add("&7Max. Stufe: &f" + roman(d.max));
            lore.add("&7Fuer: &f" + String.join(", ", d.targets).toLowerCase(Locale.ROOT));
            lore.add("&8");
            lore.add("&7Buch Stufe I: &d" + d.crystalCost + " Kristalle");
            lore.add(crystals >= d.crystalCost ? "&e▶ Klicke zum Kaufen" : "&cDu hast " + crystals + " Kristalle");
            m.set(slot++, Items.of(Material.ENCHANTED_BOOK, 1, display(d, d.max), lore), e -> {
                if (!plugin.customItems().take(p, "kristall", d.crystalCost)) {
                    Msg.send(p, "ce.crystals", "{prefix}&cDu brauchst &d{n} Mine-Kristalle&c. Die findest du beim Abbauen.", "n", String.valueOf(d.crystalCost));
                    return;
                }
                Items.giveOrDrop(p, book(d.id, 1));
                p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1, 1);
                open(p);
            });
        }
        m.set(40, Items.of(Material.AMETHYST_SHARD, 1, "&d&lMine-Kristalle: &f" + crystals, List.of("&7Kristalle findest du selten beim Abbauen", "&7von Stein und Erzen, in Meteoriten", "&7und bei Events.",
                "&7Gleiche Stufe nochmal drauf = +1 Stufe.")));
        m.fillEmpty();
        m.open(p);
    }

    // ---------------- Effekte: Waffen ----------------

    private Map<String, Integer> weaponOf(EntityDamageByEntityEvent e, Player[] holder) {
        if (e.getDamager() instanceof Player p) {
            holder[0] = p;
            return get(p.getInventory().getItemInMainHand());
        }
        if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) {
            holder[0] = p;
            String raw = pr.getPersistentDataContainer().get(arrowKey, PersistentDataType.STRING);
            Map<String, Integer> out = new LinkedHashMap<>();
            if (raw != null) for (String part : raw.split(",")) { String[] kv = part.split(":"); if (kv.length == 2) out.put(kv[0], parseInt(kv[1])); }
            return out;
        }
        return Map.of();
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player) || !(e.getProjectile() instanceof AbstractArrow arrow)) return;
        String raw = e.getBow() == null || !e.getBow().hasItemMeta() ? null : e.getBow().getItemMeta().getPersistentDataContainer().get(ceKey, PersistentDataType.STRING);
        if (raw != null) arrow.getPersistentDataContainer().set(arrowKey, PersistentDataType.STRING, raw);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        Player[] holder = new Player[1];
        Map<String, Integer> ces = weaponOf(e, holder);
        if (ces.isEmpty() || holder[0] == null) return;
        Player p = holder[0];
        if (victim instanceof Player vp && vp.equals(p)) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double dmg = e.getDamage();
        int lvl;
        if ((lvl = ces.getOrDefault("henker", 0)) > 0 && victim.getAttribute(Attribute.MAX_HEALTH) != null
                && victim.getHealth() < victim.getAttribute(Attribute.MAX_HEALTH).getValue() * 0.35) dmg *= 1 + 0.08 * lvl;
        if ((lvl = ces.getOrDefault("donner", 0)) > 0 && r.nextDouble() < 0.04 * lvl) {
            victim.getWorld().strikeLightningEffect(victim.getLocation());
            dmg += 1.5 * lvl;
        }
        e.setDamage(dmg);
        if ((lvl = ces.getOrDefault("vampir", 0)) > 0 && r.nextDouble() < 0.15 * lvl) {
            double max = p.getAttribute(Attribute.MAX_HEALTH) == null ? 20 : p.getAttribute(Attribute.MAX_HEALTH).getValue();
            p.setHealth(Math.min(max, p.getHealth() + Math.min(4, dmg * 0.1 * lvl + 0.5)));
            p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2, 0), 2, 0.3, 0.2, 0.3);
        }
        if ((lvl = ces.getOrDefault("frost", 0)) > 0 && r.nextDouble() < 0.15 * lvl) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30 + 15 * lvl, lvl - 1));
            victim.getWorld().spawnParticle(Particle.SNOWFLAKE, victim.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.01);
        }
        if ((lvl = ces.getOrDefault("gift", 0)) > 0 && r.nextDouble() < 0.12 * lvl) victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 40 + 20 * lvl, lvl - 1));
        if ((lvl = ces.getOrDefault("wither", 0)) > 0 && r.nextDouble() < 0.1 * lvl) victim.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 40 + 20 * lvl, lvl - 1));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        int total = 0;
        for (ItemStack a : p.getInventory().getArmorContents()) total += level(a, "panzer");
        if (total > 0) e.setDamage(e.getDamage() * Math.max(0.6, 1 - 0.03 * total));
    }

    // ---------------- Effekte: Werkzeuge ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakXp(BlockBreakEvent e) {
        int lvl = level(e.getPlayer().getInventory().getItemInMainHand(), "erfahrung");
        if (lvl > 0 && e.getExpToDrop() > 0) e.setExpToDrop((int) Math.round(e.getExpToDrop() * (1 + 0.5 * lvl)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        Map<String, Integer> ces = get(p.getInventory().getItemInMainHand());
        if (ces.isEmpty()) return;
        boolean smeltOn = ces.containsKey("autoschmelze");
        if (smeltOn) for (Item item : e.getItems()) {
            ItemStack st = item.getItemStack();
            Material to = smelt.get(st.getType());
            if (to != null) { item.setItemStack(new ItemStack(to, st.getAmount())); }
        }
        if (ces.containsKey("telekinese")) {
            for (var it = e.getItems().iterator(); it.hasNext(); ) {
                Item item = it.next();
                Map<Integer, ItemStack> left = p.getInventory().addItem(item.getItemStack());
                if (left.isEmpty()) it.remove();
                else item.setItemStack(left.values().iterator().next());
            }
        }
    }

    public Material smeltResult(Material m) { return smelt.get(m); }

    // ---------------- Effekte: Ruestung ----------------

    private void armorTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerInventory inv = p.getInventory();
            int tempo = level(inv.getBoots(), "tempo"), sprung = level(inv.getBoots(), "sprung");
            int nacht = level(inv.getHelmet(), "nachtsicht"), feuer = level(inv.getChestplate(), "feuerfest");
            if (tempo > 0) effect(p, PotionEffectType.SPEED, 70, tempo - 1);
            if (sprung > 0) effect(p, PotionEffectType.JUMP_BOOST, 70, sprung - 1);
            if (feuer > 0) effect(p, PotionEffectType.FIRE_RESISTANCE, 70, 0);
            if (nacht > 0) effect(p, PotionEffectType.NIGHT_VISION, 320, 0);
        }
    }

    private static void effect(Player p, PotionEffectType t, int ticks, int amp) {
        PotionEffect cur = p.getPotionEffect(t);
        if (cur != null && (cur.getAmplifier() > amp || (cur.getAmplifier() == amp && cur.getDuration() > ticks - 30))) return;
        p.addPotionEffect(new PotionEffect(t, ticks, amp, true, false, true));
    }
}
