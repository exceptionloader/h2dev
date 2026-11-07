package de.minesmp.core.events;

import de.minesmp.core.MineSMP;
import de.minesmp.core.clan.Clan;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import de.minesmp.core.utility.RewardService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Orte in der Survival-Welt, die regelmaessig auftauchen (events.yml -> pois):
 * METEOR    - Meteorit mit Beutekiste und Waechtern (wer zuerst kommt ...)
 * KAMPFZONE - Zone halten (PvP!), wer zuerst X Sekunden allein drin steht, gewinnt.
 * Alle veraenderten Bloecke werden gesichert (auch in der DB) und danach wiederhergestellt.
 */
public final class PoiService implements Listener {
    private final MineSMP plugin;
    private final NamespacedKey poiKey;
    private ConfigurationSection cfg;

    private String activeType;
    private String activeName;
    private Location center;
    private long endsAt;
    private final Map<Block, BlockData> changed = new LinkedHashMap<>();
    private final List<UUID> guards = new ArrayList<>();
    private Block chest;
    private boolean looted;
    private final Map<UUID, Integer> progress = new HashMap<>();
    private BossBar bar;
    private String lastMinute = "";

    public PoiService(MineSMP plugin) {
        this.plugin = plugin;
        this.poiKey = new NamespacedKey(plugin, "poi");
        reload();
        plugin.listen(this);
        restoreFromDb();
        plugin.command(new SimpleCommand("poi", "minesmp.event", "Meteoriten & Kampfzonen", this::cmd, (s, a) -> {
            if (a.length == 1 && s.hasPermission("minesmp.admin.poi")) return List.of("start", "stop");
            if (a.length == 2 && a[0].equalsIgnoreCase("start")) return List.of("meteor", "kampfzone");
            return List.of();
        }, "meteor", "kampfzone"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::second, 20, 20);
    }

    public void reload() {
        YamlConfiguration y = plugin.yaml("events.yml");
        YamlConfiguration def = new YamlConfiguration();
        try (var in = plugin.getResource("events.yml")) {
            if (in != null) def = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException ignored) { }
        ConfigurationSection s = y.getConfigurationSection("pois");
        cfg = s != null ? s : def.getConfigurationSection("pois") != null ? def.getConfigurationSection("pois") : new MemoryConfiguration();
    }

    public boolean active() { return activeType != null; }

    /** Text fuer %poi%: "Meteorit 120, -840" oder "-" */
    public String text() {
        if (!active() || center == null) return "-";
        return Text.strip(Text.gradients(activeName)) + " " + center.getBlockX() + ", " + center.getBlockZ();
    }

    // ---------------- Zeitplan ----------------

    private void second() {
        if (active()) {
            tick();
            return;
        }
        if (!cfg.getBoolean("enabled", true)) return;
        LocalDateTime now = LocalDateTime.now(RewardService.ZONE);
        String minute = now.getHour() + ":" + now.getMinute();
        if (minute.equals(lastMinute)) return;
        lastMinute = minute;
        int interval = Math.max(10, cfg.getInt("interval-minutes", 60));
        int offset = cfg.getInt("offset-minutes", 30);
        int mod = (now.getHour() * 60 + now.getMinute()) % interval;
        if (mod == Math.floorMod(offset - 5, interval)) Msg.broadcast("poi.soon", "{prefix}&7In &f5 Minuten &7erscheint ein &6Meteorit &7oder eine &cKampfzone &7in der Survival-Welt!");
        if (mod != offset % interval) return;
        if (Bukkit.getOnlinePlayers().size() < cfg.getInt("min-online", 1)) return;
        List<String> types = cfg.getStringList("types");
        if (types.isEmpty()) types = List.of("meteor", "kampfzone");
        start(types.get(ThreadLocalRandom.current().nextInt(types.size())), 0);
    }

    // ---------------- Start ----------------

    public void start(String type, int attempt) {
        if (active() || attempt > 10) return;
        World w = Bukkit.getWorld(cfg.getString("world", "world"));
        if (w == null) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ang = r.nextDouble(Math.PI * 2);
        double dist = r.nextDouble(cfg.getInt("min-radius", 400), Math.max(cfg.getInt("min-radius", 400) + 1, cfg.getInt("max-radius", 2500)));
        Location sp = w.getSpawnLocation();
        int x = (int) (sp.getX() + Math.cos(ang) * dist), z = (int) (sp.getZ() + Math.sin(ang) * dist);
        if (w.getWorldBorder() != null && !w.getWorldBorder().isInside(new Location(w, x, 64, z))) { start(type, attempt + 1); return; }
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(ch -> plugin.sync(() -> {
            if (active()) return;
            int y = w.getHighestBlockYAt(x, z);
            Block ground = w.getBlockAt(x, y, z);
            Material gm = ground.getType();
            boolean bad = ground.isLiquid() || gm.name().endsWith("_LEAVES") || gm == Material.ICE || y < w.getSeaLevel() - 2 || y > 200;
            if (!bad && plugin.claims() != null) {
                for (int dx = -16; dx <= 16 && !bad; dx += 16) for (int dz = -16; dz <= 16 && !bad; dz += 16)
                    if (plugin.claims().at(new Location(w, x + dx, y, z + dz)) != null) bad = true;
            }
            if (bad) { start(type, attempt + 1); return; }
            Location c = new Location(w, x + 0.5, y + 1, z + 0.5);
            if (type.equalsIgnoreCase("kampfzone")) buildZone(c); else buildMeteor(c);
        }));
    }

    private ConfigurationSection type(String id) {
        ConfigurationSection s = cfg.getConfigurationSection(id);
        return s == null ? new MemoryConfiguration() : s;
    }

    private void set(Block b, Material m) {
        if (!changed.containsKey(b)) changed.put(b, b.getBlockData());
        b.setType(m, false);
    }

    private void buildMeteor(Location c) {
        ConfigurationSection t = type("meteor");
        activeType = "meteor";
        activeName = t.getString("name", "&6&lMeteorit");
        center = c;
        endsAt = System.currentTimeMillis() + cfg.getInt("duration-minutes", 15) * 60_000L;
        World w = c.getWorld();
        Material[] mats = {Material.MAGMA_BLOCK, Material.OBSIDIAN, Material.BLACKSTONE, Material.CRYING_OBSIDIAN, Material.MAGMA_BLOCK};
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        // Krater
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > 5.2) continue;
            Block top = w.getBlockAt(cx + dx, cy - 1, cz + dz);
            if (d > 3 && r.nextDouble() < 0.6) set(top, r.nextBoolean() ? Material.COARSE_DIRT : Material.BASALT);
            for (int dy = 0; dy <= 4; dy++) {
                Block above = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                if (!above.getType().isAir() && !above.isLiquid() && d <= 4.5 && dy > 0) set(above, Material.AIR);
            }
        }
        // Gesteinsbrocken
        for (int dx = -2; dx <= 2; dx++) for (int dy = 0; dy <= 3; dy++) for (int dz = -2; dz <= 2; dz++) {
            double d = Math.sqrt(dx * dx + (dy - 1.2) * (dy - 1.2) + dz * dz);
            if (d <= 2.3) set(w.getBlockAt(cx + dx, cy + dy, cz + dz), mats[r.nextInt(mats.length)]);
        }
        chest = w.getBlockAt(cx, cy + 4, cz);
        set(chest, Material.CHEST);
        if (chest.getState() instanceof Chest st) {
            st.getPersistentDataContainer().set(poiKey, PersistentDataType.BYTE, (byte) 1);
            st.update();
            fillLoot(((Chest) chest.getState()).getBlockInventory(), t);
        }
        looted = false;
        ConfigurationSection gs = t.getConfigurationSection("guards");
        if (gs != null) for (String et : gs.getKeys(false)) {
            EntityType type;
            try { type = EntityType.valueOf(et.toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException e) { continue; }
            for (int i = 0; i < gs.getInt(et); i++) {
                Location l = c.clone().add(r.nextDouble(-4, 4), 0.2, r.nextDouble(-4, 4));
                l.setY(w.getHighestBlockYAt(l) + 1);
                Entity en = w.spawnEntity(l, type);
                en.getPersistentDataContainer().set(poiKey, PersistentDataType.BYTE, (byte) 1);
                en.setPersistent(false);
                if (en instanceof LivingEntity le) { le.setRemoveWhenFarAway(false); le.customName(Text.c("&cWaechter")); }
                guards.add(en.getUniqueId());
            }
        }
        w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 4, 0.6f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c.clone().add(0, 2, 0), 3);
        saveRestore();
        Msg.broadcast("poi.meteor", "{prefix}{name} &7eingeschlagen bei &fX {x} &7/ &fZ {z} &7(Survival)! &7Wer zuerst die Kiste pluendert, bekommt die Beute. &8/poi",
                "name", activeName, "x", String.valueOf(cx), "z", String.valueOf(cz));
    }

    private void buildZone(Location c) {
        ConfigurationSection t = type("kampfzone");
        activeType = "kampfzone";
        activeName = t.getString("name", "&c&lKampfzone");
        center = c;
        endsAt = System.currentTimeMillis() + cfg.getInt("duration-minutes", 15) * 60_000L;
        int radius = t.getInt("radius", 5);
        World w = c.getWorld();
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > radius + 0.5) continue;
            int y = w.getHighestBlockYAt(cx + dx, cz + dz);
            Block b = w.getBlockAt(cx + dx, y, cz + dz);
            if (Math.abs(y - (cy - 1)) > 6) continue;
            set(b, d > radius - 0.7 ? Material.RED_CONCRETE : (dx + dz) % 2 == 0 ? Material.BLACK_CONCRETE : Material.GRAY_CONCRETE);
        }
        set(w.getBlockAt(cx, w.getHighestBlockYAt(cx, cz) + 1, cz), Material.LODESTONE);
        progress.clear();
        bar = Bukkit.createBossBar(Text.legacy(activeName + " &7- halte die Zone!"), BarColor.RED, BarStyle.SEGMENTED_10);
        bar.setProgress(0);
        saveRestore();
        Msg.broadcast("poi.zone", "{prefix}{name} &7bei &fX {x} &7/ &fZ {z} &7(Survival)! &7Halte die Zone &f{sec}s &7allein. &cPvP!",
                "name", activeName, "x", String.valueOf(cx), "z", String.valueOf(cz), "sec", String.valueOf(t.getInt("capture-seconds", 90)));
    }

    private void fillLoot(org.bukkit.inventory.Inventory inv, ConfigurationSection t) {
        List<Map<?, ?>> loot = t.getMapList("loot");
        if (loot.isEmpty()) return;
        double total = 0;
        for (Map<?, ?> m : loot) total += num(m.get("weight"), 1);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int rolls = t.getInt("loot-rolls", 6);
        for (int i = 0; i < rolls; i++) {
            double pick = r.nextDouble(total);
            for (Map<?, ?> m : loot) {
                pick -= num(m.get("weight"), 1);
                if (pick > 0) continue;
                ItemStack it = lootItem(m);
                if (it != null) inv.setItem(r.nextInt(inv.getSize()), it);
                break;
            }
        }
    }

    private ItemStack lootItem(Map<?, ?> m) {
        int amount = (int) num(m.get("amount"), 1);
        if (m.get("custom") != null) { ItemStack it = plugin.customItems().create(String.valueOf(m.get("custom"))); if (it != null) it.setAmount(Math.max(1, amount)); return it; }
        if (m.get("key") != null) return plugin.crates().keyItem(String.valueOf(m.get("key")), Math.max(1, amount));
        if (m.get("spawner") != null && plugin.spawners() != null) return plugin.spawners().item(String.valueOf(m.get("spawner")), Math.max(1, amount));
        if (m.get("book") != null && plugin.enchants() != null) return plugin.enchants().bookFromSpec(String.valueOf(m.get("book")));
        if (m.get("material") != null) {
            MemoryConfiguration mc = new MemoryConfiguration();
            m.forEach((k, v) -> mc.set(String.valueOf(k), v));
            return Items.fromConfig(mc);
        }
        return null;
    }

    private static double num(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? def : Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return def; }
    }

    // ---------------- Ablauf ----------------

    private void tick() {
        if (System.currentTimeMillis() > endsAt) {
            Msg.broadcast("poi.end", "{prefix}{name} &7ist verschwunden.", "name", activeName);
            stop();
            return;
        }
        World w = center.getWorld();
        if (w == null) { stop(); return; }
        if (w.isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)) {
            w.spawnParticle(Particle.END_ROD, center.clone().add(0, 6, 0), 6, 0.2, 3, 0.2, 0.01);
            if (activeType.equals("meteor")) w.spawnParticle(Particle.LAVA, center.clone().add(0, 2, 0), 3, 1.2, 0.6, 1.2);
        }
        if (activeType.equals("kampfzone")) zoneTick();
    }

    private void zoneTick() {
        ConfigurationSection t = type("kampfzone");
        int radius = t.getInt("radius", 5);
        int need = Math.max(10, t.getInt("capture-seconds", 90));
        List<Player> inside = new ArrayList<>();
        for (Player p : center.getWorld().getPlayers()) {
            double dx = p.getLocation().getX() - center.getX(), dz = p.getLocation().getZ() - center.getZ();
            boolean near = dx * dx + dz * dz < 60 * 60;
            if (near && !bar.getPlayers().contains(p)) bar.addPlayer(p);
            if (!near && bar.getPlayers().contains(p)) bar.removePlayer(p);
            if (p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
            if (dx * dx + dz * dz <= (radius + 0.5) * (radius + 0.5) && Math.abs(p.getLocation().getY() - center.getY()) < 6) inside.add(p);
        }
        Player holder = null;
        if (!inside.isEmpty()) {
            Clan first = plugin.clans().of(inside.get(0));
            boolean same = true;
            for (Player p : inside) if (p != inside.get(0) && (first == null || plugin.clans().of(p) != first)) { same = false; break; }
            if (same) holder = inside.get(0);
        }
        if (holder == null) {
            bar.setTitle(Text.legacy(activeName + (inside.isEmpty() ? " &7- niemand in der Zone" : " &e- umkaempft!")));
            return;
        }
        int pr = progress.merge(holder.getUniqueId(), 1, Integer::sum);
        bar.setProgress(Math.min(1.0, pr / (double) need));
        bar.setTitle(Text.legacy(activeName + " &7- &f" + holder.getName() + " &7haelt die Zone &8(" + pr + "/" + need + "s)"));
        if (pr % 15 == 0) holder.playSound(holder.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1, 1.5f);
        if (pr >= need) {
            plugin.rewards().give(holder, t.getConfigurationSection("reward"), "Kampfzone");
            Msg.broadcast("poi.zone-won", "{prefix}&f{name} &7hat die {zone} &7erobert!", "name", holder.getName(), "zone", activeName);
            holder.playSound(holder.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1, 1);
            stop();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (chest == null || looted || !(e.getPlayer() instanceof Player p)) return;
        if (!(e.getInventory().getHolder() instanceof Chest c) || !c.getBlock().equals(chest)) return;
        looted = true;
        Msg.broadcast("poi.looted", "{prefix}&f{name} &7pluendert den {poi}&7! Er verschwindet in &f2 Minuten&7.", "name", p.getName(), "poi", activeName);
        endsAt = Math.min(endsAt, System.currentTimeMillis() + 120_000);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (active() && changed.containsKey(e.getBlock())) {
            e.setCancelled(true);
            Msg.actionbar(e.getPlayer(), "poi.nobreak", "&cDieser Block gehoert zum Event.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (active()) e.blockList().removeIf(changed::containsKey);
    }

    public void stop() {
        if (chest != null && chest.getState() instanceof Chest c) c.getBlockInventory().clear();
        List<Map.Entry<Block, BlockData>> list = new ArrayList<>(changed.entrySet());
        java.util.Collections.reverse(list);
        for (Map.Entry<Block, BlockData> en : list) en.getKey().setBlockData(en.getValue(), false);
        changed.clear();
        for (UUID id : guards) { Entity en = Bukkit.getEntity(id); if (en != null) en.remove(); }
        guards.clear();
        if (bar != null) { bar.removeAll(); bar = null; }
        activeType = null;
        activeName = null;
        chest = null;
        center = null;
        progress.clear();
        plugin.db().update("DELETE FROM kv WHERE k = ?", "poi-restore");
    }

    private void saveRestore() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Block, BlockData> en : changed.entrySet()) {
            Block b = en.getKey();
            sb.append(b.getWorld().getName()).append(';').append(b.getX()).append(';').append(b.getY()).append(';').append(b.getZ()).append(';').append(en.getValue().getAsString()).append('\n');
        }
        plugin.db().kvSet("poi-restore", sb.toString());
    }

    private void restoreFromDb() {
        plugin.db().kvGet("poi-restore").thenAccept(raw -> {
            if (raw == null || raw.isBlank()) return;
            plugin.sync(() -> {
                int n = 0;
                String[] lines = raw.split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    String[] p = lines[i].split(";", 5);
                    if (p.length < 5) continue;
                    World w = Bukkit.getWorld(p[0]);
                    if (w == null) continue;
                    try {
                        w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])).setBlockData(Bukkit.createBlockData(p[4]), false);
                        n++;
                    } catch (IllegalArgumentException ignored) { }
                }
                plugin.db().update("DELETE FROM kv WHERE k = ?", "poi-restore");
                plugin.getLogger().info("Event-Ort vom letzten Lauf zurueckgesetzt (" + n + " Bloecke).");
            });
        });
    }

    private void cmd(CommandSender s, String l, String[] a) {
        if (a.length >= 1 && s.hasPermission("minesmp.admin.poi")) {
            if (a[0].equalsIgnoreCase("stop")) { if (active()) stop(); Msg.send(s, "poi.stopped", "{prefix}&7Event-Ort beendet."); return; }
            if (a[0].equalsIgnoreCase("start")) {
                if (active()) { Msg.send(s, "poi.running", "{prefix}&cEs laeuft schon einer."); return; }
                start(a.length > 1 ? a[1] : "meteor", 0);
                Msg.send(s, "poi.starting", "{prefix}&7Suche einen Ort ...");
                return;
            }
        }
        if (!active()) {
            Msg.send(s, "poi.none", "{prefix}&7Gerade ist kein Meteorit und keine Kampfzone aktiv. Sie erscheinen regelmaessig in der Survival-Welt.");
            return;
        }
        Msg.send(s, "poi.info", "{prefix}{name} &7bei &fX {x} &7/ &fZ {z} &8| &7noch &f{time}", "name", activeName, "x", String.valueOf(center.getBlockX()),
                "z", String.valueOf(center.getBlockZ()), "time", TimeUtil.format(endsAt - System.currentTimeMillis()));
        if (s instanceof Player p && p.getWorld().equals(center.getWorld())) p.setCompassTarget(center);
    }
}
