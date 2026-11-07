package de.minesmp.core.spawner;

import de.minesmp.core.MineSMP;
import de.minesmp.core.gui.ConfirmMenu;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spawner-System (spawners.yml): Spawner erzeugen keine Mobs, sondern legen Drops und XP
 * direkt in einen Speicher (Rechtsklick = Menue). Gleiche Spawner lassen sich stapeln.
 * Kaufen: /spawner (MineCoins), Gem-Shop, Kisten. Natuerliche Spawner nur mit der Spawner-Spitzhacke.
 */
public final class SpawnerService implements Listener {
    public record Drop(Material material, int min, int max, double chance) {}
    public record Type(String id, String name, EntityType entity, Material icon, double price, int xp, List<Drop> drops) {}

    public static final class Spawner {
        final String key, world;
        final int x, y, z;
        UUID owner;
        String ownerName, type;
        int stack;
        long xp;
        final Map<Material, Integer> storage = new EnumMap<>(Material.class);
        boolean dirty;
        final List<SpawnerMenu> viewers = new ArrayList<>();

        Spawner(String key, String world, int x, int y, int z) { this.key = key; this.world = world; this.x = x; this.y = y; this.z = z; }
        int total() { int n = 0; for (int v : storage.values()) n += v; return n; }
    }

    private final MineSMP plugin;
    private final NamespacedKey typeKey;
    private final Map<String, Type> types = new LinkedHashMap<>();
    private final Map<EntityType, Type> byEntity = new EnumMap<>(EntityType.class);
    private final Map<String, Spawner> spawners = new HashMap<>();
    private YamlConfiguration cfg;

    public SpawnerService(MineSMP plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "spawner_type");
        reload();
        String text = plugin.db().isMariaDb() ? "LONGTEXT" : "TEXT";
        plugin.db().run(c -> {
            try (var st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS spawners (loc VARCHAR(128) PRIMARY KEY, owner VARCHAR(36) NOT NULL, owner_name VARCHAR(16),"
                        + " type VARCHAR(32) NOT NULL, stack INTEGER NOT NULL, xp BIGINT NOT NULL DEFAULT 0, storage " + text + ")");
            }
        });
        load();
        plugin.listen(this);
        plugin.command(new SimpleCommand("spawner", "minesmp.spawners", "Spawner-Shop", this::cmd, (s, a) -> {
            if (a.length == 1) return s.hasPermission("minesmp.admin.spawners") ? List.of("shop", "info", "give") : List.of("shop", "info");
            if (a.length == 2 && a[0].equalsIgnoreCase("give")) return null;
            if (a.length == 3 && a[0].equalsIgnoreCase("give")) return new ArrayList<>(types.keySet());
            return List.of();
        }, "spawners", "spawnershop"));
        int interval = Math.max(5, setting("interval-seconds", 30));
        Bukkit.getScheduler().runTaskTimer(plugin, this::generate, interval * 20L, interval * 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveDirty, 20 * 60, 20 * 60);
    }

    public void reload() {
        cfg = plugin.yaml("spawners.yml");
        types.clear();
        byEntity.clear();
        ConfigurationSection ts = cfg.getConfigurationSection("types");
        if (ts == null) return;
        for (String id : ts.getKeys(false)) {
            ConfigurationSection t = ts.getConfigurationSection(id);
            EntityType et;
            try { et = EntityType.valueOf(t.getString("entity", id).toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { plugin.getLogger().warning("Spawner " + id + ": unbekannter Mob"); continue; }
            List<Drop> drops = new ArrayList<>();
            ConfigurationSection ds = t.getConfigurationSection("drops");
            if (ds != null) for (String m : ds.getKeys(false)) {
                Material mat = Material.matchMaterial(m);
                if (mat == null) continue;
                ConfigurationSection d = ds.getConfigurationSection(m);
                if (d == null) drops.add(new Drop(mat, ds.getInt(m), ds.getInt(m), 1));
                else drops.add(new Drop(mat, d.getInt("min", 1), d.getInt("max", d.getInt("min", 1)), d.getDouble("chance", 1)));
            }
            Material icon = Material.matchMaterial(t.getString("icon", et.name() + "_SPAWN_EGG"));
            Type type = new Type(id.toLowerCase(Locale.ROOT), t.getString("name", Text.capitalize(id)), et, icon == null ? Material.SPAWNER : icon,
                    t.getDouble("price"), t.getInt("xp", 3), drops);
            types.put(type.id, type);
            byEntity.put(et, type);
        }
    }

    private int setting(String k, int def) { return cfg.getInt("settings." + k, def); }

    public Map<String, Type> types() { return types; }

    // ---------------- Items ----------------

    public ItemStack item(String typeId, int amount) {
        Type t = typeId == null ? null : types.get(typeId.toLowerCase(Locale.ROOT));
        if (t == null) return null;
        ItemStack it = Items.of(Material.SPAWNER, Math.min(64, Math.max(1, amount)), "&b&l" + Text.strip(Text.gradients(t.name)) + "-Spawner",
                List.of("&7Erzeugt automatisch Drops und XP.", "&7Rechtsklick auf den Spawner: &fSpeicher", "&7Gleichen Spawner draufklicken: &fStapeln", "&8MineSMP Spawner"));
        it.editMeta(m -> {
            m.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, t.id);
            m.setEnchantmentGlintOverride(true);
        });
        return it;
    }

    public String typeOf(ItemStack it) {
        if (it == null || it.getType() != Material.SPAWNER || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    public boolean isPluginSpawner(Block b) {
        return b.getType() == Material.SPAWNER && spawners.containsKey(key(b));
    }

    // ---------------- Speicher ----------------

    private void load() {
        plugin.db().queryThen(c -> {
            List<Spawner> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT loc, owner, owner_name, type, stack, xp, storage FROM spawners");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String[] p = rs.getString(1).split(";");
                    if (p.length < 4) continue;
                    Spawner s = new Spawner(rs.getString(1), p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                    s.owner = UUID.fromString(rs.getString(2));
                    s.ownerName = rs.getString(3);
                    s.type = rs.getString(4);
                    s.stack = rs.getInt(5);
                    s.xp = rs.getLong(6);
                    String st = rs.getString(7);
                    if (st != null && !st.isEmpty()) for (String part : st.split(",")) {
                        String[] kv = part.split(":");
                        Material m = kv.length == 2 ? Material.matchMaterial(kv[0]) : null;
                        if (m != null) s.storage.put(m, Integer.parseInt(kv[1]));
                    }
                    out.add(s);
                }
            }
            return out;
        }, list -> {
            for (Spawner s : list) spawners.put(s.key, s);
            if (!list.isEmpty()) plugin.getLogger().info(list.size() + " Spawner geladen.");
        });
    }

    private void save(Spawner s) {
        StringBuilder sb = new StringBuilder();
        s.storage.forEach((m, n) -> { if (n > 0) { if (sb.length() > 0) sb.append(','); sb.append(m.name()).append(':').append(n); } });
        s.dirty = false;
        plugin.db().update("REPLACE INTO spawners (loc, owner, owner_name, type, stack, xp, storage) VALUES (?, ?, ?, ?, ?, ?, ?)",
                s.key, s.owner.toString(), s.ownerName, s.type, s.stack, s.xp, sb.toString());
    }

    public void saveDirty() {
        for (Spawner s : spawners.values()) if (s.dirty) save(s);
    }

    // ---------------- Erzeugung ----------------

    private void generate() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int perStack = setting("storage-per-stack", 1728);
        int xpPerStack = setting("xp-per-stack", 5000);
        int radius = setting("player-radius", 0);
        List<Spawner> gone = new ArrayList<>();
        for (Spawner s : spawners.values()) {
            World w = Bukkit.getWorld(s.world);
            if (w == null || !w.isChunkLoaded(s.x >> 4, s.z >> 4)) continue;
            if (w.getBlockAt(s.x, s.y, s.z).getType() != Material.SPAWNER) { gone.add(s); continue; }
            if (radius > 0 && w.getNearbyPlayers(new Location(w, s.x + 0.5, s.y + 0.5, s.z + 0.5), radius).isEmpty()) continue;
            Type t = types.get(s.type);
            if (t == null) continue;
            int cap = perStack * s.stack;
            int total = s.total();
            for (int i = 0; i < s.stack && total < cap; i++) {
                for (Drop d : t.drops) {
                    if (d.chance < 1 && r.nextDouble() >= d.chance) continue;
                    int n = d.min >= d.max ? d.max : r.nextInt(d.min, d.max + 1);
                    if (n <= 0) continue;
                    n = Math.min(n, cap - total);
                    s.storage.merge(d.material, n, Integer::sum);
                    total += n;
                }
            }
            s.xp = Math.min((long) xpPerStack * s.stack, s.xp + (long) t.xp * s.stack);
            s.dirty = true;
            for (SpawnerMenu m : new ArrayList<>(s.viewers)) m.render();
        }
        for (Spawner s : gone) {
            spawners.remove(s.key);
            plugin.db().update("DELETE FROM spawners WHERE loc = ?", s.key);
            plugin.getLogger().info("Spawner bei " + s.key + " existiert nicht mehr und wurde entfernt.");
        }
    }

    private boolean canAccess(Player p, Spawner s) {
        return s.owner.equals(p.getUniqueId()) || p.hasPermission("minesmp.admin.spawners")
                || (plugin.claims() != null && plugin.claims().isTrusted(s.owner, p.getUniqueId()));
    }

    // ---------------- Events ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        String type = typeOf(e.getItemInHand());
        if (type == null) return;
        Type t = types.get(type);
        Player p = e.getPlayer();
        if (t == null || !cfg.getStringList("settings.worlds").isEmpty() && !cfg.getStringList("settings.worlds").contains(e.getBlock().getWorld().getName())) {
            e.setCancelled(true);
            Msg.send(p, "spawner.world", "{prefix}&cHier kannst du keine Spawner platzieren.");
            return;
        }
        Block b = e.getBlock();
        if (b.getState() instanceof CreatureSpawner cs) {
            cs.setSpawnedType(t.entity);
            cs.setMaxNearbyEntities(0); // erzeugt keine echten Mobs
            cs.update(true, false);
        }
        Spawner s = new Spawner(key(b), b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        s.owner = p.getUniqueId();
        s.ownerName = p.getName();
        s.type = t.id;
        s.stack = 1;
        spawners.put(s.key, s);
        save(s);
        Msg.send(p, "spawner.placed", "{prefix}&aSpawner platziert! &7Rechtsklick oeffnet den Speicher.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (b.getType() != Material.SPAWNER) return;
        Spawner s = spawners.get(key(b));
        if (s == null) return;
        Player p = e.getPlayer();
        e.setCancelled(true);
        if (!canAccess(p, s)) {
            Msg.send(p, "spawner.foreign", "{prefix}&cDieser Spawner gehoert &f{name}&c.", "name", s.ownerName);
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        String handType = typeOf(hand);
        if (handType != null) {
            if (!handType.equals(s.type)) { Msg.send(p, "spawner.wrongtype", "{prefix}&cNur gleiche Spawner lassen sich stapeln."); return; }
            int max = setting("max-stack", 64);
            int add = Math.min(p.isSneaking() ? hand.getAmount() : 1, max - s.stack);
            if (add <= 0) { Msg.send(p, "spawner.maxstack", "{prefix}&cDieser Spawner ist voll gestapelt ({max}).", "max", String.valueOf(max)); return; }
            hand.setAmount(hand.getAmount() - add);
            s.stack += add;
            save(s);
            p.playSound(b.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.6f);
            Msg.send(p, "spawner.stacked", "{prefix}&aSpawner gestapelt: &f{stack}x", "stack", String.valueOf(s.stack));
            return;
        }
        new SpawnerMenu(p, s).open(p);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.SPAWNER) return;
        Player p = e.getPlayer();
        Spawner s = spawners.get(key(b));
        if (s != null) {
            e.setCancelled(true);
            if (!canAccess(p, s)) { Msg.send(p, "spawner.foreign", "{prefix}&cDieser Spawner gehoert &f{name}&c.", "name", s.ownerName); return; }
            pickUp(p, s);
            return;
        }
        // Natuerlicher Spawner: nur mit der Spawner-Spitzhacke mitnehmen
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!"SPAWNERPICK".equals(plugin.customItems().abilityOf(hand))) return;
        if (!(b.getState() instanceof CreatureSpawner cs)) return;
        Type t = cs.getSpawnedType() == null ? null : byEntity.get(cs.getSpawnedType());
        if (t == null) {
            e.setCancelled(true);
            Msg.send(p, "spawner.unknown", "{prefix}&cDiesen Spawner kann man nicht mitnehmen.");
            return;
        }
        e.setExpToDrop(0);
        e.setDropItems(false);
        b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), item(t.id, 1));
        plugin.customItems().useCharge(p, hand);
        Msg.send(p, "spawner.mined", "{prefix}&aDu hast einen &f{type}-Spawner &aabgebaut!", "type", Text.strip(Text.gradients(t.name)));
    }

    private void pickUp(Player p, Spawner s) {
        for (SpawnerMenu m : new ArrayList<>(s.viewers)) Menu.close(m.viewer);
        double sold = sellAll(p, s);
        if (s.xp > 0) p.giveExp((int) Math.min(Integer.MAX_VALUE, s.xp));
        List<ItemStack> rest = new ArrayList<>();
        s.storage.forEach((m, n) -> { while (n > 0) { int k = Math.min(n, m.getMaxStackSize()); rest.add(new ItemStack(m, k)); n -= k; } });
        if (!rest.isEmpty()) Items.giveOrDrop(p, rest.toArray(new ItemStack[0]));
        World w = Bukkit.getWorld(s.world);
        if (w != null) w.getBlockAt(s.x, s.y, s.z).setType(Material.AIR);
        int left = s.stack;
        while (left > 0) { int k = Math.min(64, left); Items.giveOrDrop(p, item(s.type, k)); left -= k; }
        spawners.remove(s.key);
        plugin.db().update("DELETE FROM spawners WHERE loc = ?", s.key);
        Msg.send(p, "spawner.picked", "{prefix}&aSpawner abgebaut: &f{stack}x&a. &7Speicher verkauft fuer &a{money}&7.",
                "stack", String.valueOf(s.stack), "money", plugin.eco().format(sold));
    }

    private double sellAll(Player p, Spawner s) {
        double total = 0;
        for (var it = s.storage.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            double each = plugin.shop().sellPrice(p, new ItemStack(en.getKey()));
            if (each <= 0) continue;
            total += each * en.getValue();
            it.remove();
        }
        total = Math.floor(total * 100) / 100.0;
        if (total > 0) plugin.eco().deposit(p, total, "SPAWNER_SELL", s.type + " x" + s.stack);
        s.dirty = true;
        return total;
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> b.getType() == Material.SPAWNER && spawners.containsKey(key(b)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> b.getType() == Material.SPAWNER && spawners.containsKey(key(b)));
    }

    // ---------------- Befehle / Menues ----------------

    private void cmd(CommandSender sender, String l, String[] a) {
        if (a.length >= 3 && a[0].equalsIgnoreCase("give") && sender.hasPermission("minesmp.admin.spawners")) {
            Player t = Bukkit.getPlayerExact(a[1]);
            int n = 1;
            try { if (a.length > 3) n = Math.max(1, Math.min(64, Integer.parseInt(a[3]))); } catch (NumberFormatException ignored) { }
            ItemStack it = item(a[2], n);
            if (t == null || it == null) { Msg.send(sender, "spawner.invalid", "{prefix}&cSpieler oder Typ ungueltig. Typen: &f{list}", "list", String.join(", ", types.keySet())); return; }
            Items.giveOrDrop(t, it);
            plugin.audit().log(sender, "GIVE_SPAWNER", t.getName() + " " + n + "x " + a[2]);
            Msg.send(sender, "spawner.given", "{prefix}&aSpawner gegeben.");
            return;
        }
        if (!(sender instanceof Player p)) return;
        if (a.length >= 1 && a[0].equalsIgnoreCase("info")) {
            int count = 0, stacks = 0;
            for (Spawner s : spawners.values()) if (s.owner.equals(p.getUniqueId())) { count++; stacks += s.stack; }
            Msg.send(p, "spawner.info", "{prefix}&7Du besitzt &f{count} &7Spawner-Bloecke mit insgesamt &f{stacks} &7Spawnern.", "count", String.valueOf(count), "stacks", String.valueOf(stacks));
            return;
        }
        openShop(p);
    }

    public void openShop(Player p) {
        Menu m = new Menu(5, "&8Spawner-Shop") {};
        int slot = 10;
        for (Type t : types.values()) {
            if (t.price <= 0) continue;
            if (slot % 9 == 8) slot += 2;
            if (slot >= 35) break;
            List<String> lore = new ArrayList<>();
            lore.add("&7Drops pro Durchgang:");
            for (Drop d : t.drops) lore.add(" &8▪ &f" + (d.min == d.max ? d.min : d.min + "-" + d.max) + "x " + Text.capitalize(d.material.name()) + (d.chance < 1 ? " &8(" + Math.round(d.chance * 1000) / 10.0 + "%)" : ""));
            lore.add("&7XP: &a" + t.xp);
            lore.add("&8");
            lore.add("&7Preis: &a" + plugin.eco().format(t.price));
            lore.add("&e▶ Klicke zum Kaufen");
            Type type = t;
            m.set(slot++, Items.of(t.icon, 1, t.name + " &7Spawner", lore), e -> {
                ItemStack it = item(type.id, 1);
                ConfirmMenu.open(p, "&8Spawner kaufen?", it, List.of("&7Preis: &a" + plugin.eco().format(type.price)), () -> {
                    if (!plugin.eco().withdraw(p, type.price, "SPAWNER_BUY", type.id)) { Msg.send(p, "not-enough-money", "{prefix}&cDu hast nicht genug Geld."); return; }
                    Items.giveOrDrop(p, item(type.id, 1));
                    Msg.send(p, "spawner.bought", "{prefix}&aSpawner gekauft!");
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.5f);
                });
            });
        }
        m.set(40, Items.of(Material.BOOK, 1, "&b&lSo funktioniert's", List.of("&7Spawner erzeugen keine Mobs, sondern", "&7legen Drops und XP in einen Speicher.",
                "&7Rechtsklick: Speicher oeffnen", "&7Gleichen Spawner draufklicken: stapeln", "&7Schleichen = ganzer Stapel", "&7Abbauen: nur Besitzer (verkauft den Speicher)")));
        m.fillEmpty();
        m.open(p);
    }

    final class SpawnerMenu extends Menu {
        final Player viewer;
        final Spawner s;

        SpawnerMenu(Player viewer, Spawner s) {
            super(6, "&8Spawner &7" + Text.strip(Text.gradients(types.containsKey(s.type) ? types.get(s.type).name : s.type)) + " x" + s.stack);
            this.viewer = viewer;
            this.s = s;
            s.viewers.add(this);
            render();
        }

        void render() {
            clear();
            List<Map.Entry<Material, Integer>> list = new ArrayList<>(s.storage.entrySet());
            list.removeIf(en -> en.getValue() <= 0);
            int slot = 0;
            for (Map.Entry<Material, Integer> en : list) {
                if (slot >= 45) break;
                Material mat = en.getKey();
                double each = plugin.shop().sellPrice(viewer, new ItemStack(mat));
                ItemStack icon = Items.of(mat, Math.min(64, en.getValue()), "&f" + Text.capitalize(mat.name()), List.of("&7Anzahl: &f" + Text.money(en.getValue()),
                        each > 0 ? "&7Wert: &a" + plugin.eco().format(each * en.getValue()) : "&8Nicht verkaufbar", "&8", "&eLinksklick: &7Stack nehmen", "&eShift-Klick: &7Alles nehmen"));
                set(slot++, icon, e -> take(mat, e.isShiftClick()));
            }
            Type t = types.get(s.type);
            int cap = setting("storage-per-stack", 1728) * s.stack;
            set(45, Items.of(t == null ? Material.SPAWNER : t.icon, 1, "&b&lInfo", List.of("&7Besitzer: &f" + s.ownerName, "&7Stapel: &f" + s.stack + "x",
                    "&7Speicher: &f" + Text.money(s.total()) + "&7/&f" + Text.money(cap), "&7Neue Drops alle &f" + setting("interval-seconds", 30) + "s")));
            set(47, Items.of(Material.EXPERIENCE_BOTTLE, 1, "&a&lXP einsammeln", List.of("&7Gespeichert: &a" + Text.money(s.xp) + " XP")), e -> {
                if (s.xp <= 0) return;
                viewer.giveExp((int) Math.min(Integer.MAX_VALUE, s.xp));
                s.xp = 0;
                s.dirty = true;
                viewer.playSound(viewer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
                render();
            });
            double worth = 0;
            for (Map.Entry<Material, Integer> en : list) worth += Math.max(0, plugin.shop().sellPrice(viewer, new ItemStack(en.getKey()))) * en.getValue();
            set(49, Items.glow(Items.of(Material.GOLD_INGOT, 1, "&e&lAlles verkaufen", List.of("&7Wert: &a" + plugin.eco().format(worth)))), e -> {
                double sold = sellAll(viewer, s);
                if (sold <= 0) { Msg.send(viewer, "sell.nothing", "{prefix}&cDu hast nichts Verkaufbares dabei."); return; }
                Msg.send(viewer, "spawner.sold", "{prefix}&aSpeicher verkauft fuer &f{money}&a.", "money", plugin.eco().format(sold));
                viewer.playSound(viewer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1.5f);
                for (SpawnerMenu m : new ArrayList<>(s.viewers)) m.render();
            });
            set(51, Items.of(Material.IRON_PICKAXE, 1, "&c&lSpawner abbauen", List.of("&7Gibt dir den Spawner-Stapel zurueck.", "&7Der Speicher wird verkauft.", "&8Shift-Klick zum Bestaetigen")), e -> {
                if (!e.isShiftClick()) { Msg.send(viewer, "spawner.confirm", "{prefix}&7Zum Abbauen &fShift-Klick&7."); return; }
                if (!spawners.containsKey(s.key)) return;
                pickUp(viewer, s);
            });
            set(53, Items.of(Material.BARRIER, "&cSchliessen"), e -> Menu.close(viewer));
            for (int i = 45; i < 54; i++) if (inv.getItem(i) == null) inv.setItem(i, FILLER);
        }

        private void take(Material mat, boolean all) {
            int have = s.storage.getOrDefault(mat, 0);
            if (have <= 0) return;
            int want = all ? have : Math.min(have, mat.getMaxStackSize());
            int given = 0;
            while (given < want) {
                int k = Math.min(mat.getMaxStackSize(), want - given);
                Map<Integer, ItemStack> left = viewer.getInventory().addItem(new ItemStack(mat, k));
                int notFit = left.values().stream().mapToInt(ItemStack::getAmount).sum();
                given += k - notFit;
                if (notFit > 0) break;
            }
            if (given <= 0) { Msg.send(viewer, "inventory-full", "{prefix}&cDein Inventar ist voll."); return; }
            if (have - given <= 0) s.storage.remove(mat); else s.storage.put(mat, have - given);
            s.dirty = true;
            for (SpawnerMenu m : new ArrayList<>(s.viewers)) m.render();
        }

        @Override
        public void handleClick(InventoryClickEvent e) {
            super.handleClick(e);
        }

        @Override
        public void onClose(InventoryCloseEvent e) {
            s.viewers.remove(this);
        }
    }

    public void shutdown() {
        saveDirty();
    }
}
