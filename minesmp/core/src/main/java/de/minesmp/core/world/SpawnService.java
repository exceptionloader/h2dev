package de.minesmp.core.world;

import de.minesmp.core.MineSMP;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Der MineSMP-Spawn: baut sich aus dem Bauplan im Plugin (spawn/blueprint.txt.gz), setzt NPCs (Mannequins),
 * Hologramme, Portale, Kisten- und Arena-Positionen (spawn/layout.yml) und schuetzt die Spawn-Welt.
 * Befehle: /navi (Navigator), /minesmp buildspawn (neu bauen).
 */
public final class SpawnService implements Listener {
    private record Npc(String id, String name, String desc, Material item, double[] pos, String action, String skin) {}
    private record Holo(double[] pos, float scale, String text) {}
    private record Portal(String name, String action, int[] box, double[] back) {}
    private record Zone(double cx, double cz, double r, int y1, int y2) {}
    private record Area(String name, Material icon, String desc, double[] pos) {}

    private final MineSMP plugin;
    private final NamespacedKey npcKey, holoKey;
    private int version;
    private String worldName = "spawn";
    private double[] spawnPos, arenaArrival;
    private int voidY = 55;
    private final List<Zone> zones = new ArrayList<>();
    private final List<Npc> npcs = new ArrayList<>();
    private final List<Holo> holos = new ArrayList<>();
    private final List<Portal> portals = new ArrayList<>();
    private final List<Area> areas = new ArrayList<>();
    private final Map<String, int[]> crates = new LinkedHashMap<>();
    private final Map<String, double[][]> duels = new LinkedHashMap<>();
    private final List<int[]> clearBoxes = new ArrayList<>();
    private final Map<String, UUID> npcEntities = new HashMap<>();
    private final Map<Integer, UUID> holoEntities = new HashMap<>();
    private final Map<UUID, Long> clickCooldown = new HashMap<>();
    private volatile boolean built, building;

    public SpawnService(MineSMP plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "spawn_npc");
        this.holoKey = new NamespacedKey(plugin, "spawn_holo");
        loadLayout();
        plugin.listen(this);
        plugin.command(new SimpleCommand("navi", "minesmp.spawn", "Spawn-Navigator", (s, l, a) -> { if (s instanceof Player p) openNavigator(p); },
                "navigator", "spawnmenu"));
        try {
            String v = plugin.db().kvGet("spawn-built").get(5, TimeUnit.SECONDS);
            built = v != null && Integer.parseInt(v) >= version;
        } catch (Exception ignored) {
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::startup, 60);
        Bukkit.getScheduler().runTaskTimer(plugin, this::ensureEntities, 100, 100);
        Bukkit.getScheduler().runTaskTimer(plugin, this::lookAtPlayers, 80, 4);
    }

    // ================================================================ Layout
    private static double[] d(Object o) {
        if (!(o instanceof List<?> l)) return null;
        double[] out = new double[l.size()];
        for (int i = 0; i < l.size(); i++) out[i] = ((Number) l.get(i)).doubleValue();
        return out;
    }

    private static Material mat(Object o, Material def) {
        Material m = o == null ? null : Material.matchMaterial(String.valueOf(o));
        return m == null ? def : m;
    }

    @SuppressWarnings("unchecked")
    private void loadLayout() {
        YamlConfiguration y;
        File custom = new File(plugin.getDataFolder(), "spawn-layout.yml");
        if (custom.exists()) y = YamlConfiguration.loadConfiguration(custom);
        else {
            InputStream in = plugin.getResource("spawn/layout.yml");
            if (in == null) { plugin.getLogger().warning("spawn/layout.yml fehlt im Plugin."); return; }
            y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        version = y.getInt("version", 1);
        worldName = plugin.getConfig().getString("worlds.spawn", y.getString("world", "spawn"));
        spawnPos = d(y.getList("spawn"));
        arenaArrival = d(y.getList("arena-arrival"));
        voidY = y.getInt("void-y", 55);
        for (Map<?, ?> m : y.getMapList("pvp-zones")) {
            double[] c = d(m.get("center")), yy = d(m.get("y"));
            zones.add(new Zone(c[0], c[1], ((Number) m.get("radius")).doubleValue(), (int) yy[0], (int) yy[1]));
        }
        for (Map<?, ?> m : y.getMapList("npcs")) {
            npcs.add(new Npc(String.valueOf(m.get("id")), String.valueOf(m.get("name")), String.valueOf(m.get("desc")),
                    mat(m.get("item"), Material.AIR), d(m.get("pos")), String.valueOf(m.get("action")), String.valueOf(m.get("skin"))));
        }
        for (Map<?, ?> m : y.getMapList("holograms")) {
            Object sc = m.get("scale");
            holos.add(new Holo(d(m.get("pos")), sc instanceof Number n ? n.floatValue() : 1f, String.join("\n", (List<String>) m.get("text"))));
        }
        for (Map<?, ?> m : y.getMapList("portals")) {
            double[] b = d(m.get("box"));
            int[] box = new int[6];
            for (int i = 0; i < 6; i++) box[i] = (int) b[i];
            portals.add(new Portal(String.valueOf(m.get("name")), String.valueOf(m.get("action")), box, d(m.get("back"))));
        }
        for (Map<?, ?> m : y.getMapList("areas")) {
            areas.add(new Area(String.valueOf(m.get("name")), mat(m.get("icon"), Material.COMPASS), String.valueOf(m.get("desc")), d(m.get("pos"))));
        }
        var cs = y.getConfigurationSection("crates");
        if (cs != null) for (String k : cs.getKeys(false)) {
            double[] p = d(cs.getList(k));
            crates.put(k, new int[]{(int) p[0], (int) p[1], (int) p[2]});
        }
        var ds = y.getConfigurationSection("duels");
        if (ds != null) for (String k : ds.getKeys(false)) duels.put(k, new double[][]{d(ds.getList(k + ".pos1")), d(ds.getList(k + ".pos2"))});
        for (Object o : y.getList("clear-boxes", List.of())) {
            double[] b = d(o);
            clearBoxes.add(new int[]{(int) b[0], (int) b[1], (int) b[2], (int) b[3], (int) b[4], (int) b[5]});
        }
    }

    private World world() {
        return Bukkit.getWorld(worldName);
    }

    private Location loc(double[] p) {
        World w = world();
        if (w == null || p == null) return null;
        return new Location(w, p[0], p[1], p[2], p.length > 3 ? (float) p[3] : 0, p.length > 4 ? (float) p[4] : 0);
    }

    private static String locString(String world, double[] p) {
        return world + ";" + p[0] + ";" + p[1] + ";" + p[2] + ";" + (p.length > 3 ? p[3] : 0) + ";" + (p.length > 4 ? p[4] : 0);
    }

    // ================================================================ Start & automatischer Bau
    private void startup() {
        World w = world();
        if (w == null) return;
        if (built) {
            applyConfig(false);
            ensureEntities();
            return;
        }
        if (!plugin.getConfig().getBoolean("spawn-build.auto", true)) return;
        int blocks = countBlocks(w, 5000);
        if (blocks < 5000) {
            plugin.getLogger().info("Baue den MineSMP-Spawn (erster Start) ...");
            build(Bukkit.getConsoleSender());
        } else {
            plugin.getLogger().warning("Spawn-Welt enthaelt bereits Bauten - der neue Spawn wird nicht automatisch gebaut. Befehl: /minesmp buildspawn");
        }
    }

    private Set<long[]> chunksOfBoxes() {
        Set<Long> seen = new HashSet<>();
        Set<long[]> out = new java.util.LinkedHashSet<>();
        for (int[] b : clearBoxes) {
            for (int cx = Math.floorDiv(b[0], 16); cx <= Math.floorDiv(b[3], 16); cx++)
                for (int cz = Math.floorDiv(b[2], 16); cz <= Math.floorDiv(b[5], 16); cz++)
                    if (seen.add(((long) cx << 32) ^ (cz & 0xffffffffL))) out.add(new long[]{cx, cz});
        }
        return out;
    }

    /** Zaehlt vorhandene Bloecke in den Baubereichen (bis zum Limit). */
    private int countBlocks(World w, int limit) {
        int n = 0;
        for (long[] c : chunksOfBoxes()) {
            Chunk ch = w.getChunkAt((int) c[0], (int) c[1]);
            ChunkSnapshot s = ch.getChunkSnapshot(false, false, false);
            for (int sy = 0; sy < (w.getMaxHeight() - w.getMinHeight()) >> 4; sy++) {
                if (s.isSectionEmpty(sy)) continue;
                int y0 = w.getMinHeight() + sy * 16;
                for (int y = y0; y < y0 + 16; y++)
                    for (int x = 0; x < 16; x++)
                        for (int z = 0; z < 16; z++)
                            if (!s.getBlockType(x, y, z).isAir() && ++n >= limit) return n;
            }
        }
        return n;
    }

    /** Baut den Spawn aus dem Bauplan. Laeuft ueber mehrere Ticks, damit der Server nicht haengt. */
    public void build(CommandSender by) {
        World w = world();
        if (w == null) { Msg.send(by, "spawn.no-world", "{prefix}&cDie Spawn-Welt existiert nicht."); return; }
        if (building) { Msg.send(by, "spawn.building", "{prefix}&cDer Spawn wird bereits gebaut."); return; }
        List<BlockData> palette = new ArrayList<>();
        List<int[]> runs = new ArrayList<>();
        int bad = 0;
        try (InputStream raw = plugin.getResource("spawn/blueprint.txt.gz");
             BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#P ")) {
                    String[] p = line.split(" ", 3);
                    BlockData bd;
                    try { bd = Bukkit.createBlockData(p[2]); } catch (IllegalArgumentException e) { bd = null; bad++; plugin.getLogger().warning("Bauplan: unbekannter Block " + p[2]); }
                    palette.add(bd);
                } else if (!line.startsWith("#") && !line.isBlank()) {
                    String[] p = line.split(" ");
                    runs.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4])});
                }
            }
        } catch (Exception e) {
            Msg.send(by, "spawn.blueprint-error", "{prefix}&cBauplan konnte nicht gelesen werden: &f{err}", "err", String.valueOf(e.getMessage()));
            return;
        }
        building = true;
        long t0 = System.currentTimeMillis();
        Msg.send(by, "spawn.build-start", "{prefix}&7Baue den Spawn &8({runs} Abschnitte) &7...", "runs", String.valueOf(runs.size()));
        // Spieler kurz in Sicherheit bringen
        World safe = Bukkit.getWorlds().get(0);
        List<Player> moved = new ArrayList<>(w.getPlayers());
        for (Player p : moved) p.teleport(safe.getSpawnLocation());
        for (Entity e : w.getEntities()) if (e.getPersistentDataContainer().has(npcKey) || e.getPersistentDataContainer().has(holoKey)) e.remove();
        npcEntities.clear();
        holoEntities.clear();
        // Chunks laden und festhalten
        List<Chunk> held = new ArrayList<>();
        for (long[] c : chunksOfBoxes()) {
            Chunk ch = w.getChunkAt((int) c[0], (int) c[1]);
            ch.addPluginChunkTicket(plugin);
            held.add(ch);
        }
        // Zu loeschende Bloecke sammeln
        ArrayDeque<int[]> toClear = new ArrayDeque<>();
        for (Chunk ch : held) {
            ChunkSnapshot s = ch.getChunkSnapshot(false, false, false);
            for (int[] b : clearBoxes) {
                int bx1 = Math.max(b[0], ch.getX() * 16), bx2 = Math.min(b[3], ch.getX() * 16 + 15);
                int bz1 = Math.max(b[2], ch.getZ() * 16), bz2 = Math.min(b[5], ch.getZ() * 16 + 15);
                if (bx1 > bx2 || bz1 > bz2) continue;
                for (int y = Math.max(b[1], w.getMinHeight()); y <= Math.min(b[4], w.getMaxHeight() - 1); y++) {
                    if (s.isSectionEmpty((y - w.getMinHeight()) >> 4)) { y = ((y - w.getMinHeight()) | 15) + w.getMinHeight(); continue; }
                    for (int x = bx1; x <= bx2; x++)
                        for (int z = bz1; z <= bz2; z++)
                            if (!s.getBlockType(x & 15, y, z & 15).isAir()) toClear.add(new int[]{x, y, z});
                }
            }
        }
        BlockData air = Material.AIR.createBlockData();
        int total = runs.size();
        int badFinal = bad;
        new BukkitRunnable() {
            int runIdx = 0;
            long placed = 0;
            @Override public void run() {
                int budget = plugin.getConfig().getInt("spawn-build.blocks-per-tick", 30000);
                while (budget > 0 && !toClear.isEmpty()) {
                    int[] p = toClear.poll();
                    w.getBlockAt(p[0], p[1], p[2]).setBlockData(air, false);
                    budget--;
                }
                while (budget > 0 && runIdx < total) {
                    int[] r = runs.get(runIdx++);
                    BlockData bd = palette.get(r[0]);
                    if (bd != null) for (int x = r[3]; x <= r[4]; x++) { w.getBlockAt(x, r[1], r[2]).setBlockData(bd, false); budget--; placed++; }
                }
                if (toClear.isEmpty() && runIdx >= total) {
                    cancel();
                    for (Chunk ch : held) ch.removePluginChunkTicket(plugin);
                    building = false;
                    built = true;
                    plugin.db().kvSet("spawn-built", String.valueOf(version));
                    applyConfig(true);
                    ensureEntities();
                    Location sp = loc(spawnPos);
                    for (Player p : moved) if (p.isOnline() && sp != null) p.teleport(sp);
                    plugin.audit().log(by, "SPAWN_BUILD", placed + " Bloecke");
                    long ms = System.currentTimeMillis() - t0;
                    Msg.send(by, "spawn.build-done", "{prefix}&aSpawn gebaut! &7{n} Bloecke in {t}{bad}", "n", Text.money(placed),
                            "t", ms < 1000 ? ms + "ms" : TimeUtil.format(ms), "bad", badFinal > 0 ? " &c(" + badFinal + " unbekannte Blocktypen)" : "");
                    if (!(by instanceof Player)) plugin.getLogger().info("Spawn gebaut: " + placed + " Bloecke.");
                }
            }
        }.runTaskTimer(plugin, 1, 1);
    }

    /** Spawn-Punkt, Kisten, Duell-Arenen und Arena-Spawn in die Konfiguration uebernehmen. */
    private void applyConfig(boolean force) {
        var cfg = plugin.getConfig();
        boolean changed = false;
        String cur = cfg.getString("spawn", "");
        if (spawnPos != null && (force || cur.isEmpty() || cur.equals(worldName + ";0.5;100;0.5;0;0"))) {
            cfg.set("spawn", locString(worldName, spawnPos));
            World w = world();
            if (w != null) w.setSpawnLocation(loc(spawnPos));
            changed = true;
        }
        var arenas = cfg.getConfigurationSection("duels.arenas");
        if (force || arenas == null || arenas.getKeys(false).isEmpty()) {
            for (var e : duels.entrySet()) {
                cfg.set("duels.arenas." + e.getKey() + ".pos1", locString(worldName, e.getValue()[0]));
                cfg.set("duels.arenas." + e.getKey() + ".pos2", locString(worldName, e.getValue()[1]));
            }
            changed = true;
        }
        if (arenaArrival != null && (force || cfg.getString("duels.ffa-spawn", "").isEmpty())) {
            cfg.set("duels.ffa-spawn", locString(worldName, arenaArrival));
            changed = true;
        }
        if (changed) plugin.saveConfig();
        if (force || !plugin.crates().hasLocations()) {
            Map<String, String> m = new LinkedHashMap<>();
            for (var e : crates.entrySet()) m.put(worldName + ";" + e.getValue()[0] + ";" + e.getValue()[1] + ";" + e.getValue()[2], e.getKey());
            plugin.crates().setLocations(m);
        }
    }

    // ================================================================ NPCs & Hologramme
    private void ensureEntities() {
        World w = world();
        if (w == null || !built || building) return;
        for (Npc n : npcs) {
            UUID id = npcEntities.get(n.id);
            Entity e = id == null ? null : Bukkit.getEntity(id);
            if (e != null && e.isValid()) continue;
            Location l = loc(n.pos);
            if (l == null || !w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            Mannequin m = w.spawn(l, Mannequin.class, mq -> {
                mq.setPersistent(false);
                mq.setInvulnerable(true);
                mq.setSilent(true);
                mq.setImmovable(true);
                mq.setGravity(false);
                mq.setRemoveWhenFarAway(false);
                mq.customName(Text.c(n.name));
                mq.setCustomNameVisible(true);
                mq.setDescription(Text.c(n.desc));
                try {
                    boolean slim = n.skin.startsWith("slim");
                    mq.setProfile(ResolvableProfile.resolvableProfile().skinPatch(sp -> sp
                            .body(Key.key("minecraft", "entity/player/" + n.skin))
                            .model(slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC)).build());
                } catch (Exception ignored) {
                }
                if (n.item != Material.AIR) mq.getEquipment().setItemInMainHand(new ItemStack(n.item));
                mq.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, n.id);
            });
            npcEntities.put(n.id, m.getUniqueId());
        }
        for (int i = 0; i < holos.size(); i++) {
            Holo h = holos.get(i);
            UUID id = holoEntities.get(i);
            Entity e = id == null ? null : Bukkit.getEntity(id);
            if (e != null && e.isValid()) continue;
            Location l = loc(h.pos);
            if (l == null || !w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            TextDisplay td = w.spawn(l, TextDisplay.class, t -> {
                t.setPersistent(false);
                t.text(Text.c(h.text));
                t.setBillboard(Display.Billboard.CENTER);
                t.setShadowed(true);
                t.setLineWidth(400);
                t.setBackgroundColor(Color.fromARGB(0x70, 0x0B, 0x14, 0x1C));
                t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(h.scale, h.scale, h.scale), new AxisAngle4f()));
                t.getPersistentDataContainer().set(holoKey, PersistentDataType.BYTE, (byte) 1);
            });
            holoEntities.put(i, td.getUniqueId());
        }
    }

    /** NPCs schauen den naechsten Spieler an. */
    private void lookAtPlayers() {
        World w = world();
        if (w == null || w.getPlayers().isEmpty()) return;
        for (Npc n : npcs) {
            UUID id = npcEntities.get(n.id);
            Entity e = id == null ? null : Bukkit.getEntity(id);
            if (e == null || !e.isValid()) continue;
            Player near = null;
            double best = 64;
            for (Player p : w.getPlayers()) {
                double dd = p.getLocation().distanceSquared(e.getLocation());
                if (dd < best) { best = dd; near = p; }
            }
            if (near == null) {
                e.setRotation((float) (n.pos.length > 3 ? n.pos[3] : 0), 0);
                continue;
            }
            Location a = e.getLocation().add(0, 1.6, 0), b = near.getEyeLocation();
            double dx = b.getX() - a.getX(), dy = b.getY() - a.getY(), dz = b.getZ() - a.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            e.setRotation(yaw, pitch);
        }
    }

    private String npcId(Entity e) {
        return e.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onNpcClick(PlayerInteractEntityEvent e) {
        String id = npcId(e.getRightClicked());
        if (id == null) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) clickNpc(e.getPlayer(), id);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onNpcHit(EntityDamageByEntityEvent e) {
        String id = npcId(e.getEntity());
        if (id == null) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p) clickNpc(p, id);
    }

    /** Linksklick: unverwundbare NPCs loesen kein Schadens-Ereignis aus, daher das Angriffs-Ereignis von Paper. */
    @EventHandler(priority = EventPriority.LOW)
    public void onNpcAttack(io.papermc.paper.event.player.PrePlayerAttackEntityEvent e) {
        String id = npcId(e.getAttacked());
        if (id == null) return;
        e.setCancelled(true);
        clickNpc(e.getPlayer(), id);
    }

    private void clickNpc(Player p, String id) {
        long now = System.currentTimeMillis();
        if (now - clickCooldown.getOrDefault(p.getUniqueId(), 0L) < 500) return;
        clickCooldown.put(p.getUniqueId(), now);
        for (Npc n : npcs) {
            if (!n.id.equals(id)) continue;
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
            runAction(p, n.action, null);
            return;
        }
    }

    /** Fuehrt eine Aktion aus: cmd:<befehl>, msg:<text>, navi, warp:spawn|arena, rtp:<welt>, farmwelt. false = nicht moeglich. */
    private boolean runAction(Player p, String action, Portal from) {
        if (action.startsWith("cmd:")) { p.performCommand(action.substring(4)); return true; }
        if (action.startsWith("msg:")) { p.sendMessage(Text.c(Msg.get("prefix", "&8[&b&lMine&f&lSMP&8] &7") + action.substring(4))); return true; }
        if (action.equals("navi")) { openNavigator(p); return true; }
        if (action.startsWith("warp:")) {
            Location to = action.equals("warp:arena") ? loc(arenaArrival) : loc(spawnPos);
            if (to == null) return false;
            p.teleportAsync(to);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
            return true;
        }
        if (action.startsWith("rtp:") || action.equals("farmwelt")) {
            if (plugin.combat().isTagged(p)) { Msg.send(p, "combat.blocked", "{prefix}&cDas kannst du im Kampf nicht tun."); return false; }
            String wn = action.equals("farmwelt") ? plugin.getConfig().getString("worlds.farmworld.name", "farmwelt") : action.substring(4);
            World target = Bukkit.getWorld(wn);
            if (target == null) { Msg.send(p, "farmwelt.unavailable", "{prefix}&cDiese Welt ist gerade nicht verfuegbar."); return false; }
            long cd = plugin.cooldowns().remaining(p.getUniqueId(), "rtp");
            if (cd > 0 && !p.hasPermission("minesmp.rtp.nocooldown")) {
                Msg.send(p, "cooldown", "{prefix}&cBitte warte noch &f{time}&c.", "time", TimeUtil.format(cd));
                return false;
            }
            plugin.cooldowns().set(p.getUniqueId(), "rtp", plugin.getConfig().getLong("rtp.cooldown-seconds", 300) * 1000);
            Msg.send(p, "rtp.searching", "{prefix}&7Suche einen sicheren Ort...");
            plugin.teleport().findSafe(target, 0).thenAccept(l -> plugin.sync(() -> {
                if (!p.isOnline()) return;
                if (l == null) { plugin.cooldowns().set(p.getUniqueId(), "rtp", 1); Msg.send(p, "rtp.failed", "{prefix}&cKein sicherer Ort gefunden. Versuche es erneut."); return; }
                p.teleportAsync(l).thenAccept(ok -> { if (ok) p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1f); });
            }));
            return true;
        }
        return false;
    }

    // ================================================================ Navigator
    public void openNavigator(Player p) {
        Menu m = new Menu(3, "&8» &b&lMINESMP &8Navigator") {};
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        for (int i = 0; i < areas.size() && i < slots.length; i++) {
            Area a = areas.get(i);
            m.set(slots[i], Items.of(a.icon, 1, a.name, List.of(a.desc, "", "&bKlicke zum Teleportieren")), ev -> {
                Menu.close(p);
                Location l = loc(a.pos);
                if (l == null) return;
                if (plugin.combat().isTagged(p)) { Msg.send(p, "combat.blocked", "{prefix}&cDas kannst du im Kampf nicht tun."); return; }
                if (p.getWorld().getName().equals(worldName)) {
                    p.teleportAsync(l);
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.3f);
                } else plugin.teleport().teleport(p, l, Text.strip(a.name));
            });
        }
        m.fillEmpty();
        m.open(p);
    }

    /** Andere Plugins (z.B. WorldGuard "mob-spawning deny") duerfen unsere NPCs nicht verhindern. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNpcSpawn(org.bukkit.event.entity.CreatureSpawnEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(npcKey)) e.setCancelled(false);
    }

    // ================================================================ Portale & Void
    private Portal portalAt(Location l) {
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        for (Portal p : portals) {
            int[] b = p.box;
            if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) return p;
        }
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock() || !e.getTo().getWorld().getName().equals(worldName)) return;
        Player p = e.getPlayer();
        if (e.getTo().getY() < voidY) {
            Location back = isPvpZone(e.getFrom()) || (arenaArrival != null && e.getTo().getZ() > 95) ? loc(arenaArrival) : loc(spawnPos);
            if (back != null) Bukkit.getScheduler().runTask(plugin, () -> p.teleport(back));
            return;
        }
        Portal portal = portalAt(e.getTo());
        if (portal == null) return;
        if (!plugin.cooldowns().tryUse(p.getUniqueId(), "spawn-portal", 1500)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!runAction(p, portal.action, portal) && portal.back != null) {
                Location b = loc(portal.back);
                if (b != null) p.teleport(b);
            }
        });
    }

    @EventHandler
    public void onPortal(PlayerPortalEvent e) {
        if (e.getFrom().getWorld().getName().equals(worldName)) e.setCancelled(true);
    }

    @EventHandler
    public void onEntityPortal(EntityPortalEvent e) {
        if (e.getFrom().getWorld().getName().equals(worldName)) e.setCancelled(true);
    }

    // ================================================================ Schutz
    public boolean isPvpZone(Location l) {
        if (l == null || l.getWorld() == null || !l.getWorld().getName().equals(worldName)) return false;
        for (Zone z : zones) {
            if (l.getY() >= z.y1 && l.getY() <= z.y2 && Math.hypot(l.getX() - z.cx, l.getZ() - z.cz) <= z.r) return true;
        }
        return false;
    }

    private boolean inSpawn(World w) {
        return w != null && w.getName().equals(worldName);
    }

    private boolean protectedFor(Player p, World w) {
        return inSpawn(w) && !p.hasPermission("minesmp.spawn.build");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (protectedFor(e.getPlayer(), e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (protectedFor(e.getPlayer(), e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) { if (protectedFor(e.getPlayer(), e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) { if (protectedFor(e.getPlayer(), e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || !protectedFor(e.getPlayer(), b.getWorld())) return;
        if (e.getAction() == Action.PHYSICAL && b.getType() == Material.FARMLAND) { e.setCancelled(true); return; }
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && b.getState(false) instanceof Container && !plugin.crates().isCrateBlock(b)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHanging(HangingBreakByEntityEvent e) {
        if (inSpawn(e.getEntity().getWorld()) && !(e.getRemover() instanceof Player p && p.hasPermission("minesmp.spawn.build"))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) { if (protectedFor(e.getPlayer(), e.getRightClicked().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { if (inSpawn(e.getLocation().getWorld())) e.blockList().clear(); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { if (inSpawn(e.getBlock().getWorld())) e.blockList().clear(); }

    @EventHandler(ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) { if (inSpawn(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (inSpawn(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (inSpawn(e.getBlock().getWorld()) && !(e.getPlayer() != null && e.getPlayer().hasPermission("minesmp.spawn.build"))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) { if (inSpawn(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { if (inSpawn(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) { if (inSpawn(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || !inSpawn(victim.getWorld())) return;
        Player attacker = e.getDamager() instanceof Player p ? p : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p2 ? p2 : null;
        if (attacker == null || attacker.equals(victim)) return;
        if (plugin.duels().inDuel(victim)) return;
        if (isPvpZone(victim.getLocation()) && isPvpZone(attacker.getLocation())) {
            if (!plugin.clans().sameOrAllied(attacker.getUniqueId(), victim.getUniqueId())) e.setCancelled(false);
        } else {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !inSpawn(p.getWorld())) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
            e.setCancelled(true);
            Location back = loc(spawnPos);
            if (back != null) Bukkit.getScheduler().runTask(plugin, () -> p.teleport(back));
            return;
        }
        if (plugin.duels().inDuel(p) || isPvpZone(p.getLocation())) return;
        if (!(e instanceof EntityDamageByEntityEvent)) e.setCancelled(true); // Fall, Feuer, Ersticken usw. am Spawn
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && inSpawn(p.getWorld()) && !isPvpZone(p.getLocation()) && e.getFoodLevel() < p.getFoodLevel()) e.setCancelled(true);
    }

    public boolean isBuilt() { return built; }

}
