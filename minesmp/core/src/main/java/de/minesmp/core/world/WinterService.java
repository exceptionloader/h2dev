package de.minesmp.core.world;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Snowable;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Winter am Spawn (config.yml -> winter): Schnee auf allen freien Flaechen, Schneefall (verschneites Biom + Niederschlag
 * nur in der Spawn-Welt), Abendstimmung, ein Weihnachtsbaum mit Lichtern und Geschenken, ein Weihnachtsmann, der das
 * taegliche Geschenk verteilt, und leise Weihnachtslieder aus Glockentoenen (gemeinfreie Lieder, abschaltbar in /settings).
 * Alles laesst sich wieder entfernen: /minesmp winter off
 */
public final class WinterService implements Listener {
    private final MineSMP plugin;
    private boolean applied, working, stateLoaded;
    private String originalBiome = "minecraft:plains";
    private String treeData = ""; // gemerkte Bloecke unter dem Weihnachtsbaum (x;y;z;blockdata je Zeile)

    // ---- Musik: Lied als Folge "Note:Dauer" (Dauer in Schlaegen, Standard 1), tick = Ticks pro Schlag
    private record Song(String name, int tick, String notes) {}
    private static final List<Song> SONGS = List.of(
            new Song("Jingle Bells", 7, "E4 E4 E4:2 E4 E4 E4:2 E4 G4 C4:1.5 D4:.5 E4:4 F4 F4 F4:1.5 F4:.5 F4 E4 E4 E4:.5 E4:.5 E4 D4 D4 E4 D4:2 G4:2 "
                    + "E4 E4 E4:2 E4 E4 E4:2 E4 G4 C4:1.5 D4:.5 E4:4 F4 F4 F4:1.5 F4:.5 F4 E4 E4 E4:.5 E4:.5 G4 G4 F4 D4 C4:4"),
            new Song("O Tannenbaum", 12, "C4 F4:.75 F4:.25 F4:1.5 G4:.5 A4:.75 A4:.25 A4:1.5 A4:.5 G4:.5 A4:.5 A#4 E4 G4 F4:1.5 "
                    + "C5:.5 C5:.5 A4:.5 D5:1.5 C5:.5 C5:.5 A#4:.5 A#4:1.5 A#4:.5 A#4:.5 G4:.5 C5:1.5 A#4:.5 A#4:.5 A4:.5 A4 "
                    + "C4 F4:.75 F4:.25 F4:1.5 G4:.5 A4:.75 A4:.25 A4:1.5 A4:.5 G4:.5 A4:.5 A#4 E4 G4 F4:3"),
            new Song("We Wish You a Merry Christmas", 8, "D4 G4 G4:.5 A4:.5 G4:.5 F#4:.5 E4 E4 E4 A4 A4:.5 B4:.5 A4:.5 G4:.5 F#4 D4 D4 B4 B4:.5 C5:.5 B4:.5 A4:.5 "
                    + "G4 E4 D4:.5 D4:.5 E4 A4 F#4 G4:2 D4 G4 G4 G4 F#4:2 F#4 G4 F#4 E4 D4:2 A4 B4 A4 G4 D5 D4 D4:.5 D4:.5 E4 A4 F#4 G4:3"),
            new Song("Stille Nacht", 7, "G4:1.5 A4:.5 G4 E4:3 G4:1.5 A4:.5 G4 E4:3 D5:2 D5 B4:3 C5:2 C5 G4:3 A4:2 A4 C5:1.5 B4:.5 A4 G4:1.5 A4:.5 G4 E4:3 "
                    + "A4:2 A4 C5:1.5 B4:.5 A4 G4:1.5 A4:.5 G4 E4:3 D5:2 D5 F5:1.5 D5:.5 B4 C5:3 E5:3 C5 G4 E4 G4:1.5 F4:.5 D4 C4:6"),
            new Song("Deck the Halls", 7, "G4:1.5 F4:.5 E4 D4 C4 D4 E4 C4 D4:.5 E4:.5 F4:.5 D4:.5 E4:1.5 D4:.5 C4 B3 C4:2 "
                    + "G4:1.5 F4:.5 E4 D4 C4 D4 E4 C4 D4:.5 E4:.5 F4:.5 D4:.5 E4:1.5 D4:.5 C4 B3 C4:2 "
                    + "D4:1.5 E4:.5 F4 D4 E4:1.5 F4:.5 G4 D4 E4:.5 F#4:.5 G4 A4:.5 B4:.5 C5 B4 A4 G4:2 "
                    + "G4:1.5 F4:.5 E4 D4 C4 D4 E4 C4 A4:.5 A4:.5 A4:.5 A4:.5 G4:1.5 F4:.5 E4 D4 C4:2"));
    private final List<List<int[]>> parsed = new ArrayList<>(); // je Lied: {Start-Tick, Notenblock-Ton 0-24}
    private final List<Integer> lengths = new ArrayList<>();
    private int songIdx, songPos, pause = 100;

    public WinterService(MineSMP plugin) {
        this.plugin = plugin;
        for (Song s : SONGS) parse(s);
        plugin.listen(this);
        plugin.db().kvGet("winter-state").thenAccept(v -> plugin.db().kvGet("winter-biome").thenAccept(b -> plugin.db().kvGet("winter-tree").thenAccept(t -> plugin.sync(() -> {
            applied = "on".equals(v);
            if (b != null && !b.isBlank()) originalBiome = b;
            if (t != null) treeData = t;
            stateLoaded = true;
        })))).exceptionally(err -> {
            plugin.getLogger().warning("Winter-Status konnte nicht geladen werden: " + err);
            return null;
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::sync, 160, 100);
        Bukkit.getScheduler().runTaskTimer(plugin, this::musicTick, 200, 1);
    }

    public boolean enabled() { return plugin.getConfig().getBoolean("winter.enabled", true); }

    private World world() {
        return plugin.spawn() == null ? null : Bukkit.getWorld(plugin.spawn().worldName());
    }

    /** Nach einem Neubau des Spawns ist der Schnee weg: Winter neu anwenden. */
    public void onSpawnRebuilt() {
        applied = false;
        treeData = "";
        plugin.spawn().removeNpc("santa");
        plugin.db().kvSet("winter-state", "off");
        plugin.db().kvSet("winter-tree", "");
        Bukkit.getScheduler().runTaskLater(plugin, this::sync, 40);
    }

    public void sync() {
        World w = world();
        if (!stateLoaded || working || w == null || !plugin.spawn().isBuilt() || plugin.spawn().isBuilding()) return;
        if (enabled() && !applied) run(w, true);
        else if (!enabled() && applied) run(w, false);
        else if (enabled()) {
            keepWeather(w);
            santa();
        }
    }

    private void keepWeather(World w) {
        if (!plugin.getConfig().getBoolean("winter.snowfall", true)) return;
        if (!w.hasStorm()) w.setStorm(true);
        if (w.isThundering()) w.setThundering(false);
        w.setWeatherDuration(Integer.MAX_VALUE / 2);
    }

    /** Admin: /minesmp winter on|off */
    public void set(CommandSender by, boolean on) {
        plugin.getConfig().set("winter.enabled", on);
        plugin.saveConfig();
        Msg.send(by, on ? "winter.on" : "winter.off", on ? "{prefix}&bWinter am Spawn wird eingerichtet ..." : "{prefix}&7Winter am Spawn wird entfernt ...");
        sync();
    }

    // ================================================================ Schnee, Biom, Baum
    private static boolean inBoxes(List<int[]> boxes, int x, int z) {
        for (int[] b : boxes) if (x >= b[0] && x <= b[3] && z >= b[2] && z <= b[5]) return true;
        return false;
    }

    private Biome biome(String key) {
        NamespacedKey k = NamespacedKey.fromString(key);
        Biome b = k == null ? null : Registry.BIOME.get(k);
        return b == null ? Biome.PLAINS : b;
    }

    private void run(World w, boolean on) {
        working = true;
        List<int[]> boxes = new ArrayList<>();
        for (int[] b : plugin.spawn().clearBoxes()) if (b[3] > -200) boxes.add(b); // die Grueften liegen unter der Erde
        Set<Long> seen = new LinkedHashSet<>();
        List<int[]> chunks = new ArrayList<>();
        for (int[] b : boxes) // ein Chunk Rand, damit es auch an den Kanten schneit statt regnet
            for (int cx = Math.floorDiv(b[0], 16) - 1; cx <= Math.floorDiv(b[3], 16) + 1; cx++)
                for (int cz = Math.floorDiv(b[2], 16) - 1; cz <= Math.floorDiv(b[5], 16) + 1; cz++)
                    if (seen.add(((long) cx << 32) ^ (cz & 0xffffffffL))) chunks.add(new int[]{cx, cz});
        boolean snow = plugin.getConfig().getBoolean("winter.snow-ground", true);
        boolean fall = plugin.getConfig().getBoolean("winter.snowfall", true);
        if (on && !applied) {
            Location sp = w.getSpawnLocation();
            String cur = w.getBiome(sp.getBlockX(), 100, sp.getBlockZ()).getKey().toString();
            if (!cur.contains("snowy")) { originalBiome = cur; plugin.db().kvSet("winter-biome", cur); }
        }
        Biome target = on ? Biome.SNOWY_PLAINS : biome(originalBiome);
        BlockData layer = Material.SNOW.createBlockData(), air = Material.AIR.createBlockData();
        if (!on) restoreTree(w);
        new BukkitRunnable() {
            int idx = 0;
            @Override public void run() {
                for (int n = 0; n < 6 && idx < chunks.size(); n++, idx++) {
                    int[] c = chunks.get(idx);
                    Chunk ch = w.getChunkAt(c[0], c[1]);
                    int bx = c[0] << 4, bz = c[1] << 4;
                    if (fall || !on) {
                        for (int x = bx; x < bx + 16; x += 4) for (int z = bz; z < bz + 16; z += 4) {
                            for (int y = 40; y <= 252; y += 4) w.setBiome(x, y, z, target);
                        }
                    }
                    if (!snow && on) continue;
                    for (int x = bx; x < bx + 16; x++) for (int z = bz; z < bz + 16; z++) {
                        if (!inBoxes(boxes, x, z)) continue;
                        int y = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
                        if (y < 40) continue;
                        Block top = ch.getBlock(x & 15, y, z & 15);
                        Material m = top.getType();
                        if (on) {
                            if (y + 1 >= w.getMaxHeight() || !snowable(top)) continue;
                            top.getRelative(0, 1, 0).setBlockData(layer, false);
                            if (top.getBlockData() instanceof Snowable s) { s.setSnowy(true); top.setBlockData(s, false); }
                        } else if (m == Material.SNOW) {
                            top.setBlockData(air, false);
                            Block below = top.getRelative(0, -1, 0);
                            if (below.getBlockData() instanceof Snowable s && s.isSnowy()) { s.setSnowy(false); below.setBlockData(s, false); }
                        }
                    }
                }
                if (idx < chunks.size()) return;
                cancel();
                if (on) {
                    if (plugin.getConfig().getBoolean("winter.tree", true) && treeData.isBlank()) buildTree(w);
                    santa();
                    if (fall) keepWeather(w);
                    w.setTime(plugin.getConfig().getLong("winter.time", 12200));
                } else {
                    w.setStorm(false);
                    w.setTime(6000);
                    plugin.spawn().removeNpc("santa");
                }
                for (int[] c : chunks) if (w.isChunkLoaded(c[0], c[1])) w.refreshChunk(c[0], c[1]);
                applied = on;
                working = false;
                plugin.db().kvSet("winter-state", on ? "on" : "off");
                plugin.getLogger().info(on ? "Winter am Spawn eingerichtet (Schnee, Schneefall, Weihnachtsbaum)." : "Winter am Spawn entfernt.");
            }
        }.runTaskTimer(plugin, 1, 1);
    }

    /** Schnee bleibt nur auf Flaechen mit voller Oberseite liegen (auch auf Blaettern), nie auf unsichtbaren Sperren, Eis oder Wegen. */
    private static boolean snowable(Block b) {
        Material m = b.getType();
        if (m == Material.BARRIER || m == Material.LIGHT || m == Material.SNOW || m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE
                || m == Material.STRUCTURE_VOID || b.isLiquid()) return false;
        if (Tag.LEAVES.isTagged(m)) return true;
        return b.getBlockData().isFaceSturdy(BlockFace.UP, BlockSupport.FULL);
    }

    // ---- Weihnachtsbaum (alle geaenderten Bloecke werden gemerkt und beim Entfernen zurueckgesetzt)
    private void buildTree(World w) {
        Location base = plugin.spawn().point("winter-tree");
        if (base == null) return;
        Map<Block, BlockData> changed = new LinkedHashMap<>();
        Random r = new Random(24);
        int x = base.getBlockX(), y = base.getBlockY(), z = base.getBlockZ();
        String[] lights = {"ochre_froglight[axis=y]", "pearlescent_froglight[axis=y]", "red_wool", "ochre_froglight[axis=y]", "light_blue_wool", "yellow_wool"};
        for (int k = 0; k <= 11; k++) put(w, changed, x, y + k, z, k < 11 ? "spruce_log[axis=y]" : "spruce_leaves[persistent=true]");
        for (int k = 0; k < 10; k++) { // Etagen: abwechselnd breit und schmal, nach oben spitz
            double rad = treeRadius(k);
            int ri = (int) Math.ceil(rad);
            for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > rad || (dx == 0 && dz == 0)) continue;
                boolean edge = d > rad - 1.0;
                String b = edge && r.nextDouble() < 0.2 ? lights[r.nextInt(lights.length)] : "spruce_leaves[persistent=true]";
                put(w, changed, x + dx, y + 2 + k, z + dz, b);
                double above = k == 9 ? 0 : treeRadius(k + 1);
                if (b.startsWith("spruce") && d > above && r.nextDouble() < 0.7) put(w, changed, x + dx, y + 3 + k, z + dz, "snow[layers=1]");
            }
        }
        put(w, changed, x, y + 12, z, "glowstone");
        put(w, changed, x, y + 13, z, "end_rod[facing=up]");
        String[][] gifts = {{"red_wool", "lime_carpet"}, {"light_blue_wool", "yellow_carpet"}, {"purple_wool", "white_carpet"}, {"lime_wool", "red_carpet"}, {"yellow_wool", "blue_carpet"}, {"white_wool", "red_carpet"}};
        int[][] spots = {{2, 1}, {-2, 2}, {1, -2}, {-1, -3}, {3, -1}, {-3, 0}};
        for (int i = 0; i < spots.length; i++) {
            int gx = x + spots[i][0], gz = z + spots[i][1];
            if (!w.getBlockAt(gx, y - 1, gz).getType().isSolid()) continue;
            put(w, changed, gx, y, gz, gifts[i][0]);
            put(w, changed, gx, y + 1, gz, gifts[i][1]);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Block, BlockData> en : changed.entrySet()) {
            Block b = en.getKey();
            sb.append(b.getX()).append(';').append(b.getY()).append(';').append(b.getZ()).append(';').append(en.getValue().getAsString()).append('\n');
        }
        treeData = sb.toString();
        plugin.db().kvSet("winter-tree", treeData);
    }

    /** Weihnachtsmann neben dem Baum (verteilt das taegliche Geschenk). */
    private void santa() {
        boolean want = plugin.getConfig().getBoolean("winter.santa", true);
        if (!want) { plugin.spawn().removeNpc("santa"); return; }
        if (plugin.spawn().hasNpc("santa")) return;
        Location l = plugin.spawn().point("winter-santa");
        if (l == null) return;
        plugin.spawn().addNpc("santa", "&c&lWeihnachtsmann", "&7Hol dir dein taegliches Geschenk", Material.CHEST,
                new double[]{l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()}, "cmd:" + plugin.getConfig().getString("winter.santa-command", "daily"), "wide/steve", "santa");
    }

    private static double treeRadius(int k) {
        return Math.max(1.0, 3.7 - k * 0.3 - (k % 2 == 1 ? 0.6 : 0));
    }

    private void put(World w, Map<Block, BlockData> changed, int x, int y, int z, String data) {
        Block b = w.getBlockAt(x, y, z);
        if (!changed.containsKey(b)) changed.put(b, b.getBlockData());
        try {
            b.setBlockData(Bukkit.createBlockData("minecraft:" + data), false);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void restoreTree(World w) {
        if (treeData.isBlank()) return;
        String[] lines = treeData.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String[] p = lines[i].split(";", 4);
            if (p.length < 4) continue;
            try {
                w.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])).setBlockData(Bukkit.createBlockData(p[3]), false);
            } catch (IllegalArgumentException ignored) {
            }
        }
        treeData = "";
        plugin.db().kvSet("winter-tree", "");
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent e) {
        World w = world();
        if (w != null && e.getWorld().equals(w) && enabled() && applied && plugin.getConfig().getBoolean("winter.snowfall", true) && !e.toWeatherState()) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCauldron(CauldronLevelChangeEvent e) {
        World w = world();
        if (w != null && e.getBlock().getWorld().equals(w) && e.getEntity() == null) e.setCancelled(true);
    }

    // ================================================================ Musik
    private void parse(Song s) {
        String[] names = {"F#3", "G3", "G#3", "A3", "A#3", "B3", "C4", "C#4", "D4", "D#4", "E4", "F4", "F#4", "G4", "G#4", "A4", "A#4", "B4", "C5", "C#5", "D5", "D#5", "E5", "F5", "F#5"};
        List<int[]> out = new ArrayList<>();
        double t = 0;
        for (String tok : s.notes.trim().split("\\s+")) {
            String[] p = tok.split(":");
            double beats = p.length > 1 ? Double.parseDouble(p[1]) : 1;
            int note = java.util.Arrays.asList(names).indexOf(p[0]);
            if (note >= 0) out.add(new int[]{(int) Math.round(t * s.tick), note});
            t += beats;
        }
        parsed.add(out);
        lengths.add((int) Math.round(t * s.tick));
    }

    private void musicTick() {
        if (!enabled() || !applied || !plugin.getConfig().getBoolean("winter.music", true)) return;
        if (pause > 0) { pause--; return; }
        List<int[]> song = parsed.get(songIdx);
        List<Player> listeners = null;
        for (int[] n : song) {
            if (n[0] != songPos) continue;
            if (listeners == null) listeners = listeners();
            float vol = (float) plugin.getConfig().getDouble("winter.music-volume", 0.25);
            float pitch = (float) Math.pow(2, (n[1] - 12) / 12.0);
            for (Player p : listeners) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.RECORDS, vol, pitch);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.RECORDS, vol * 0.4f, pitch);
            }
        }
        if (++songPos > lengths.get(songIdx) + 10) {
            songPos = 0;
            songIdx = (songIdx + 1) % parsed.size();
            pause = plugin.getConfig().getInt("winter.music-pause-seconds", 25) * 20;
        }
    }

    private List<Player> listeners() {
        List<Player> out = new ArrayList<>();
        World w = world();
        if (w == null) return out;
        for (Player p : w.getPlayers()) {
            if (plugin.duels().inDuel(p) || (plugin.pve() != null && plugin.pve().inRun(p))) continue;
            if (plugin.data().get(p).setting("winter-music", true)) out.add(p);
        }
        return out;
    }
}
