package de.minesmp.core.admin;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Waechter ("KI-System"): erkennt Cheater automatisch und bannt sie permanent (config.yml -> guard).
 * <ul>
 *   <li><b>X-Ray:</b> zaehlt versteckte Diamant-/Antiker-Schrott-Adern pro abgebautem Block und prueft mit einem
 *       statistischen Test, ob die Fundrate ueber dem liegt, was ehrliches Minen hergibt (Hoehlenfunde zaehlen nicht).</li>
 *   <li><b>Zugriffe durch Waende</b> (Freecam / Ghost-Hand): Behaelter oeffnen, obwohl zwischen Auge und Klickpunkt ein voller Block liegt.</li>
 *   <li><b>Hack-Clients:</b> Alarme von GrimAC (Reichweite, Killaura, Fly, Speed ...) ueber den Konsolenbefehl /guard grim.</li>
 * </ul>
 * Aus allen Hinweisen entsteht eine Gesamt-Sicherheit (0-100). Ab alert-at wird das Team gewarnt, ab ban-at wird gebannt -
 * auf Wunsch erst nach einer Gegenpruefung durch ein KI-Modell (guard.ai). Reines Zuschauen mit Freecam sieht kein Server.
 */
public final class GuardService implements Listener {
    private static final int[] WINDOWS = {300, 600, 1000};
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    /** Laufende Beobachtung eines Spielers. */
    private static final class Track {
        long fillerOver, fillerNether;                       // abgebaute Bloecke unter Tage (Oberwelt / Nether)
        final ArrayDeque<Long> hitsOver = new ArrayDeque<>(), hitsNether = new ArrayDeque<>(); // Zaehlerstand bei jedem versteckten Fund
        final ArrayDeque<long[]> oreRecent = new ArrayDeque<>();   // zuletzt abgebaute Erze {x,y,z,zeit}
        final LinkedHashMap<Long, Long> dug = new LinkedHashMap<>(256, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, Long> e) { return size() > 800; }
        };
        final LinkedHashMap<Long, Long> wall = new LinkedHashMap<>();  // Behaelter durch die Wand: Block -> Zeit
        final Map<String, long[]> grim = new HashMap<>();              // Kategorie -> {hoechster Zaehler, Zeit}
        long lastAlert, reviewBlockedUntil;
        double lastAlertLevel;
        boolean reviewing, dirty;
    }

    private record Score(double total, double xray, double wall, double grim, String kind, List<String> lines, String json) {}

    private final MineSMP plugin;
    private final Map<UUID, Track> tracks = new HashMap<>();
    private GuardAi ai;
    private String aiKeyUsed = "";

    public GuardService(MineSMP plugin) {
        this.plugin = plugin;
        plugin.listen(this);
        plugin.command(new SimpleCommand("guard", "minesmp.staff.guard", "Anti-Cheat-Waechter", this::command, this::complete, "ki", "waechter", "anticheat"));
        Bukkit.getScheduler().runTaskLater(plugin, this::setupGrim, 60);
        Bukkit.getScheduler().runTaskTimer(plugin, this::persistAll, 1200, 1200);
    }

    // ================================================================ Einstellungen
    private ConfigurationSection cfg() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("guard");
        return s == null ? new org.bukkit.configuration.MemoryConfiguration() : s;
    }

    public boolean enabled() { return cfg().getBoolean("enabled", true); }
    private boolean banMode() { return "ban".equalsIgnoreCase(cfg().getString("action", "ban")); }
    private double banAt() { return cfg().getDouble("ban-at", 95); }
    private double alertAt() { return cfg().getDouble("alert-at", 60); }
    private boolean aiOn() { return cfg().getBoolean("ai.enabled", false) && !cfg().getString("ai.api-key", "").isBlank(); }

    private boolean skip(Player p) {
        return !enabled() || p.hasPermission("minesmp.guard.bypass") || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR;
    }

    private static boolean bedrock(Player p) { return p.getUniqueId().getMostSignificantBits() == 0; }

    // ================================================================ Speicherung der Zaehler
    private Track track(Player p) {
        return tracks.computeIfAbsent(p.getUniqueId(), id -> {
            Track t = new Track();
            PlayerData d = plugin.data().get(p);
            if (d != null && d.guard != null && !d.guard.isBlank()) {
                try {
                    for (String part : d.guard.split(";")) {
                        String[] kv = part.split("=", 2);
                        if (kv.length < 2) continue;
                        switch (kv[0]) {
                            case "fo" -> t.fillerOver = Long.parseLong(kv[1]);
                            case "fn" -> t.fillerNether = Long.parseLong(kv[1]);
                            case "ho" -> { for (String s : kv[1].split(",")) if (!s.isEmpty()) t.hitsOver.add(Long.parseLong(s)); }
                            case "hn" -> { for (String s : kv[1].split(",")) if (!s.isEmpty()) t.hitsNether.add(Long.parseLong(s)); }
                            default -> { }
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return t;
        });
    }

    private void persist(Player p, Track t) {
        PlayerData d = plugin.data().get(p);
        if (d == null) return;
        StringBuilder sb = new StringBuilder("fo=").append(t.fillerOver).append(";ho=");
        for (long h : t.hitsOver) sb.append(h).append(',');
        sb.append(";fn=").append(t.fillerNether).append(";hn=");
        for (long h : t.hitsNether) sb.append(h).append(',');
        d.guard = sb.toString();
        t.dirty = false;
    }

    private void persistAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Track t = tracks.get(p.getUniqueId());
            if (t != null && t.dirty) persist(p, t);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Track t = tracks.remove(e.getPlayer().getUniqueId());
        if (t != null) persist(e.getPlayer(), t);
    }

    // ================================================================ X-Ray
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static boolean diamond(Material m) { return m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!cfg().getBoolean("xray.enabled", true) || skip(p)) return;
        Block b = e.getBlock();
        World w = b.getWorld();
        if (plugin.spawn() != null && plugin.spawn().isSpawnWorld(w)) return;
        boolean nether = w.getEnvironment() == World.Environment.NETHER;
        if (w.getEnvironment() == World.Environment.THE_END) return;
        if (!nether && b.getY() > cfg().getInt("xray.max-y", 16)) return;
        Material m = b.getType();
        boolean ore = nether ? m == Material.ANCIENT_DEBRIS : diamond(m);
        Track t = track(p);
        long now = System.currentTimeMillis();
        t.dug.put(key(b.getX(), b.getY(), b.getZ()), now);
        if (!ore) {
            if (nether) t.fillerNether++; else t.fillerOver++;
            if (((nether ? t.fillerNether : t.fillerOver) & 63) == 0) t.dirty = true;
            return;
        }
        // Gehoert der Block zu einer Ader, an der der Spieler gerade schon abbaut?
        t.oreRecent.removeIf(o -> now - o[3] > 300_000);
        boolean known = false;
        for (long[] o : t.oreRecent) {
            if (Math.abs(o[0] - b.getX()) <= 5 && Math.abs(o[1] - b.getY()) <= 5 && Math.abs(o[2] - b.getZ()) <= 5) { known = true; break; }
        }
        t.oreRecent.add(new long[]{b.getX(), b.getY(), b.getZ(), now});
        while (t.oreRecent.size() > 60) t.oreRecent.poll();
        if (known || exposed(b, m, nether, t)) return;   // Hoehlenfunde und bereits angefangene Adern zaehlen nicht
        ArrayDeque<Long> hits = nether ? t.hitsNether : t.hitsOver;
        long filler = nether ? t.fillerNether : t.fillerOver;
        hits.add(filler);
        int window = Math.max(1000, cfg().getInt("xray.window-blocks", 1500));
        while (!hits.isEmpty() && hits.peek() < filler - window) hits.poll();
        t.dirty = true;
        evaluate(p, t);
    }

    /** Liegt irgendein Block der Ader an Luft/Wasser/Lava, ohne dass der Spieler diese Stelle selbst freigelegt hat? */
    private boolean exposed(Block start, Material m, boolean nether, Track t) {
        ArrayDeque<Block> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(start);
        seen.add(key(start.getX(), start.getY(), start.getZ()));
        int n = 0;
        while (!queue.isEmpty() && n++ < 24) {
            Block b = queue.poll();
            for (BlockFace f : FACES) {
                Block nb = b.getRelative(f);
                Material nm = nb.getType();
                long k = key(nb.getX(), nb.getY(), nb.getZ());
                if (nether ? nm == Material.ANCIENT_DEBRIS : diamond(nm)) {
                    if (seen.add(k)) queue.add(nb);
                } else if (!nm.isOccluding() && !t.dug.containsKey(k)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** P(X >= v) fuer eine Poisson-Verteilung mit Erwartungswert lambda. */
    static double poissonTail(int v, double lambda) {
        if (v <= 0) return 1;
        if (lambda <= 0) return 0;
        double logTerm = -lambda + v * Math.log(lambda);
        for (int i = 2; i <= v; i++) logTerm -= Math.log(i);
        double term = Math.exp(logTerm), sum = 0;
        for (int k = v; k < v + 2000 && term > 0; k++) {
            sum += term;
            term *= lambda / (k + 1);
            if (term < sum * 1e-17) break;
        }
        return Math.min(1, sum);
    }

    /** Anzeige in Prozent - nie 100, weil kein Hinweis absolut sicher ist. */
    private static long pct(double v) { return Math.min(99, Math.round(v)); }

    private static double confidence(double p) {
        return p <= 0 ? 99.9 : Math.max(0, Math.min(99.9, -20 * Math.log10(p)));
    }

    /** Bewertet eine Erzklasse: bestes Ergebnis ueber mehrere Fenster, p-Wert um die Zahl der Fenster korrigiert. */
    private double xray(String name, String label, long filler, ArrayDeque<Long> hits, double legitPer1000, List<String> lines, StringBuilder json) {
        int minVeins = cfg().getInt("xray.min-veins", 10);
        int[] windows = {WINDOWS[0], WINDOWS[1], WINDOWS[2], Math.max(1000, cfg().getInt("xray.window-blocks", 1500))};
        double bestP = 1;
        int bestV = 0;
        long bestF = 0;
        for (int win : windows) {
            long from = Math.max(0, filler - win);
            long f = filler - from;
            int v = 0;
            for (long h : hits) if (h >= from) v++;
            if (v < minVeins || f < 150) continue;
            double p = Math.min(1, poissonTail(v, legitPer1000 / 1000.0 * f) * windows.length);
            if (p < bestP) { bestP = p; bestV = v; bestF = f; }
        }
        if (bestV == 0) {
            if (!hits.isEmpty()) lines.add("&7" + label + ": &f" + hits.size() + " &7versteckte Adern bei &f" + filler + " &7Bloecken &8(zu wenig fuer eine Bewertung)");
            return 0;
        }
        // mittlerer Abstand zwischen zwei Funden
        List<Long> gaps = new ArrayList<>();
        Long prev = null;
        for (long h : hits) { if (prev != null) gaps.add(h - prev); prev = h; }
        gaps.sort(null);
        long median = gaps.isEmpty() ? 0 : gaps.get(gaps.size() / 2);
        double rate = bestV * 1000.0 / bestF, conf = confidence(bestP);
        lines.add(String.format(Locale.GERMANY, "&7%s: &f%d &7versteckte Adern in &f%d &7Bloecken = &c%.0f pro 1000 &8(ehrlich hoechstens %.0f) &7Abstand ~&f%d &7-> &e%d%%",
                label, bestV, bestF, rate, legitPer1000, median, pct(conf)));
        json.append(json.length() > 0 ? "," : "").append(String.format(Locale.ROOT,
                "\"%s\":{\"hidden_veins\":%d,\"blocks_mined\":%d,\"veins_per_1000\":%.1f,\"legit_upper_bound_per_1000\":%.1f,\"p_value\":%.3g,\"median_gap\":%d}",
                name, bestV, bestF, rate, legitPer1000, bestP, median));
        return conf;
    }

    // ================================================================ Zugriffe durch Waende (Freecam / Ghost-Hand)
    private static boolean container(Material m) {
        return switch (m) {
            case CHEST, TRAPPED_CHEST, BARREL, HOPPER, DISPENSER, DROPPER, FURNACE, BLAST_FURNACE, SMOKER, BREWING_STAND, ENDER_CHEST -> true;
            default -> Tag.SHULKER_BOXES.isTagged(m);
        };
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        if (!cfg().getBoolean("freecam.enabled", true) || !container(b.getType()) || skip(p) || bedrock(p)) return;
        if (p.isInsideVehicle() || p.isGliding() || p.isRiptiding() || p.isSwimming()) return;
        if (plugin.spawn() != null && plugin.spawn().isSpawnWorld(b.getWorld())) return; // am Spawn gibt es nichts zu holen, Deko soll keinen Fehlalarm ausloesen
        Location point = e.getInteractionPoint();
        Vector target = point != null ? point.toVector() : b.getLocation().add(0.5, 0.5, 0.5).toVector();
        if (!blocked(p, b, target)) return;
        Track t = track(p);
        long now = System.currentTimeMillis();
        t.wall.put(key(b.getX(), b.getY(), b.getZ()), now);
        evaluate(p, t);
    }

    /**
     * true nur, wenn von jeder denkbaren Augenposition (stehend, schleichend, kriechend, leicht versetzt) ein voller,
     * undurchsichtiger Block zwischen Auge und Klickpunkt liegt. Im Zweifel fuer den Spieler.
     */
    private boolean blocked(Player p, Block clicked, Vector target) {
        World w = p.getWorld();
        Location feet = p.getLocation();
        double[] heights = {p.getEyeHeight(), 1.62, 1.27, 0.4};
        double[][] offs = {{0, 0}, {0.3, 0}, {-0.3, 0}, {0, 0.3}, {0, -0.3}};
        for (double h : heights) for (double[] o : offs) {
            Vector origin = new Vector(feet.getX() + o[0], feet.getY() + h, feet.getZ() + o[1]);
            Vector dir = target.clone().subtract(origin);
            double dist = dir.length();
            if (dist < 0.3) return false;
            RayTraceResult r = w.rayTraceBlocks(origin.toLocation(w), dir.normalize(), dist + 0.2, FluidCollisionMode.NEVER, true);
            if (r == null || r.getHitBlock() == null) return false;
            Block hit = r.getHitBlock();
            if (hit.equals(clicked) || !hit.getType().isOccluding()) return false;
            if (r.getHitPosition().distance(target) < 0.6) return false;
        }
        return true;
    }

    private double wall(Track t, List<String> lines, StringBuilder json) {
        long window = cfg().getLong("freecam.window-minutes", 15) * 60_000L, now = System.currentTimeMillis();
        t.wall.values().removeIf(time -> now - time > window);
        int n = t.wall.size();
        if (n == 0) return 0;
        int alertHits = Math.max(1, cfg().getInt("freecam.alert-hits", 4)), banHits = Math.max(alertHits + 1, cfg().getInt("freecam.ban-hits", 10));
        double conf = n < alertHits ? n * 10.0 : Math.min(99.9, alertAt() + (n - alertHits) * (banAt() - alertAt()) / (banHits - alertHits));
        lines.add(String.format(Locale.GERMANY, "&7Durch Waende: &c%d &7verschiedene Behaelter in %d Min. geoeffnet, obwohl ein Block davor war -> &e%d%%",
                n, window / 60_000, pct(conf)));
        json.append(String.format(Locale.ROOT, "{\"distinct_containers\":%d,\"window_minutes\":%d}", n, window / 60_000));
        return conf;
    }

    // ================================================================ GrimAC
    /** Ordnet einen GrimAC-Check (z.B. "TickTimer", "BadPacketsX", "AimModulo360") einer Kategorie zu. */
    private static String grimCategory(String check) {
        String c = check.toLowerCase(Locale.ROOT);
        if (has(c, "reach", "hitbox")) return "reach";
        if (has(c, "aim", "interact", "multiactions", "killaura")) return "combat";
        if (has(c, "knockback", "explosion", "antikb")) return "knockback";
        if (has(c, "simulation", "groundspoof", "timer", "nofall", "phase", "elytra", "vehicle", "noslow", "sprint")) return "movement";
        if (has(c, "badpackets", "packetorder", "crash", "exploit", "transaction")) return "packets";
        if (has(c, "autoclicker")) return "autoclicker";
        if (has(c, "post")) return "post";
        return "misc";
    }

    private static boolean has(String c, String... parts) {
        for (String p : parts) if (c.contains(p)) return true;
        return false;
    }

    private static final Map<String, double[]> GRIM_DEFAULTS = Map.of( // Kategorie -> {Gewicht, Zaehler fuer volle Wertung}
            "reach", new double[]{0.85, 30}, "combat", new double[]{0.8, 40}, "knockback", new double[]{0.85, 40},
            "movement", new double[]{0.75, 300}, "packets", new double[]{0.7, 80}, "autoclicker", new double[]{0.5, 100},
            "post", new double[]{0.3, 100}, "misc", new double[]{0.5, 100});

    private static final Map<String, String> GRIM_LABEL = Map.of("reach", "Reichweite", "combat", "Killaura/Zielhilfe", "knockback", "Kein Rueckstoss",
            "movement", "Bewegung (Fly/Speed/Timer)", "packets", "Manipulierte Pakete", "autoclicker", "Autoklicker", "post", "Paket-Reihenfolge", "misc", "Sonstiges");

    /** Konsole (GrimAC): /guard grim <Spieler> <Check> <Zaehler> */
    private void grimReport(String name, String check, String vlRaw) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null || !cfg().getBoolean("grim.enabled", true) || skip(p)) return;
        long vl;
        try { vl = (long) Double.parseDouble(vlRaw); } catch (NumberFormatException e) { return; }
        Track t = track(p);
        String cat = grimCategory(check);
        long[] cur = t.grim.get(cat);
        long now = System.currentTimeMillis();
        if (cur == null || vl > cur[0] || now - cur[1] > cfg().getLong("grim.window-minutes", 30) * 60_000L) t.grim.put(cat, new long[]{vl, now});
        else cur[1] = now;
        evaluate(p, t);
    }

    private double grim(Track t, List<String> lines, StringBuilder json) {
        long window = cfg().getLong("grim.window-minutes", 30) * 60_000L, now = System.currentTimeMillis();
        t.grim.values().removeIf(v -> now - v[1] > window);
        if (t.grim.isEmpty()) return 0;
        double miss = 1;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, long[]> en : t.grim.entrySet()) {
            double[] def = GRIM_DEFAULTS.getOrDefault(en.getKey(), GRIM_DEFAULTS.get("misc"));
            double weight = Math.max(0, Math.min(0.99, cfg().getDouble("grim.weights." + en.getKey(), def[0])));
            double strength = Math.min(1, en.getValue()[0] / def[1]);
            miss *= 1 - weight * strength;
            sb.append(sb.length() > 0 ? "&7, " : "").append("&f").append(GRIM_LABEL.getOrDefault(en.getKey(), en.getKey())).append(" &8(").append(en.getValue()[0]).append(")");
            json.append(json.length() > 0 ? "," : "").append("{\"category\":\"").append(en.getKey()).append("\",\"max_vl\":").append(en.getValue()[0]).append('}');
        }
        double conf = Math.min(99.9, (1 - miss) * 100);
        lines.add(String.format(Locale.GERMANY, "&7GrimAC: %s &7-> &e%d%%", sb, pct(conf)));
        return conf;
    }

    // ================================================================ Gesamtwertung und Entscheidung
    private Score score(Player p, Track t) {
        List<String> lines = new ArrayList<>();
        StringBuilder xj = new StringBuilder(), wj = new StringBuilder(), gj = new StringBuilder();
        double xd = xray("diamond", "Diamanten", t.fillerOver, t.hitsOver, cfg().getDouble("xray.legit-rate.diamond", 20), lines, xj);
        double xn = xray("debris", "Antiker Schrott", t.fillerNether, t.hitsNether, cfg().getDouble("xray.legit-rate.debris", 12), lines, xj);
        double x = Math.max(xd, xn), wl = wall(t, lines, wj), g = grim(t, lines, gj);
        double total = Math.min(99.9, (1 - (1 - x / 100) * (1 - wl / 100) * (1 - g / 100)) * 100);
        String kind = x >= wl && x >= g ? "X-Ray" : wl >= g ? "Zugriffe durch Waende" : "Hack-Client";
        PlayerData d = plugin.data().get(p);
        String json = String.format(Locale.ROOT, "{\"player\":\"anonym\",\"playtime_hours\":%.1f,\"bedrock_client\":%s,\"xray\":{%s},\"wall_access\":%s,\"grim\":[%s],\"local_confidence\":%.1f,\"suspected\":\"%s\"}",
                d == null ? 0 : d.playtime / 3_600_000.0, bedrock(p), xj, wj.length() == 0 ? "null" : wj, gj, total, kind);
        return new Score(total, x, wl, g, kind, lines, json);
    }

    private void evaluate(Player p, Track t) {
        if (!enabled() || t.reviewing) return;
        Score s = score(p, t);
        long now = System.currentTimeMillis();
        if (s.total >= banAt() && banMode() && now >= t.reviewBlockedUntil) {
            decide(p, t, s);
        } else if (s.total >= alertAt() && (now - t.lastAlert > 300_000 || s.total >= t.lastAlertLevel + 10)) {
            t.lastAlert = now;
            t.lastAlertLevel = s.total;
            alert(p, s, null);
        }
    }

    private void decide(Player p, Track t, Score s) {
        if (!aiOn()) { ban(p, t, s, null); return; }
        t.reviewing = true;
        UUID id = p.getUniqueId();
        String key = cfg().getString("ai.api-key", ""), model = cfg().getString("ai.model", "claude-opus-5-5"), url = cfg().getString("ai.base-url", "");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            GuardAi.Result r;
            try {
                synchronized (this) {
                    if (ai == null || !aiKeyUsed.equals(key + "|" + model + "|" + url)) { ai = new GuardAi(key, model, url); aiKeyUsed = key + "|" + model + "|" + url; }
                }
                r = ai.review(s.json);
            } catch (Throwable ex) { // auch fehlende Bibliothek
                r = new GuardAi.Result(null, "KI-Bibliothek nicht verfuegbar: " + ex);
            }
            GuardAi.Result res = r;
            plugin.sync(() -> {
                t.reviewing = false;
                Player online = Bukkit.getPlayer(id);
                if (online == null) return;
                long now = System.currentTimeMillis();
                if (res.verdict() == null) {
                    plugin.getLogger().warning("Waechter: KI-Gegenpruefung fehlgeschlagen - " + res.error());
                    if ("ban".equalsIgnoreCase(cfg().getString("ai.on-error", "alert"))) { ban(online, t, s, "KI nicht erreichbar (" + res.error() + ") - Entscheidung des Statistik-Systems"); return; }
                    t.reviewBlockedUntil = now + 600_000;
                    alert(online, s, "&cKI-Gegenpruefung fehlgeschlagen: &f" + res.error() + " &7- bitte selbst pruefen.");
                    return;
                }
                String text = (res.verdict().cheating ? "bestaetigt" : "widerspricht") + " (" + res.verdict().confidence + "%): " + res.verdict().reason;
                if (res.verdict().cheating && res.verdict().confidence >= cfg().getInt("ai.min-confidence", 85)) {
                    ban(online, t, s, "KI " + text);
                } else {
                    t.reviewBlockedUntil = now + 1_800_000;
                    saveCase(online, s, "KI_ABGELEHNT", "KI " + text);
                    alert(online, s, "&eKI-Gegenpruefung " + text + " &7- kein automatischer Bann.");
                }
            });
        });
    }

    private static String caseId() {
        return "K" + Long.toString(System.currentTimeMillis() / 1000, 36).toUpperCase(Locale.ROOT);
    }

    private String saveCase(Player p, Score s, String action, String aiText) {
        String id = caseId();
        StringBuilder ev = new StringBuilder();
        for (String l : s.lines) ev.append(l).append('\n');
        plugin.db().update("INSERT INTO guard_cases (id, time, uuid, name, kind, confidence, action, evidence, ai) VALUES (?,?,?,?,?,?,?,?,?)",
                id, System.currentTimeMillis(), p.getUniqueId().toString(), p.getName(), s.kind, s.total, action, ev.toString(), aiText == null ? "" : aiText);
        return id;
    }

    private void ban(Player p, Track t, Score s, String aiText) {
        String id = saveCase(p, s, "BAN", aiText);
        String reason = cfg().getString("ban-reason", "Cheating ({type}) - erkannt vom KI-System, Fall #{case}").replace("{type}", s.kind).replace("{case}", id);
        String name = p.getName();
        plugin.getLogger().warning("Waechter: " + name + " wird permanent gebannt (" + s.kind + ", " + pct(s.total) + "%, Fall #" + id + ")");
        notifyStaff("&c&lKI-System &8┃ &f" + name + " &cwurde permanent gebannt &8(&f" + s.kind + "&8, &e" + pct(s.total) + "%&8) &7Fall &f#" + id, s, aiText);
        plugin.webhook().send("punishments", "KI-System: " + name + " gebannt", "Art: " + s.kind + "\nSicherheit: " + pct(s.total) + "%\nFall: #" + id
                + "\n" + Text.strip(String.join("\n", s.lines)) + (aiText == null ? "" : "\n" + aiText), 0xB71C1C);
        // Zaehler leeren: nach einer Entbannung faengt der Spieler bei null an
        t.hitsOver.clear(); t.hitsNether.clear(); t.fillerOver = 0; t.fillerNether = 0; t.wall.clear(); t.grim.clear(); t.oreRecent.clear();
        persist(p, t);
        plugin.punish().apply(Bukkit.getConsoleSender(), cfg().getString("name", "KI-System"), p, "BAN", 0, reason);
    }

    private void alert(Player p, Score s, String extra) {
        notifyStaff("&e&lKI-System &8┃ &f" + p.getName() + " &7ist auffaellig: &f" + s.kind + " &8(&e" + pct(s.total) + "%&8) &7- &b/guard check " + p.getName(), s, extra);
        plugin.audit().log("KI-System", "GUARD_ALERT", p.getName() + " " + s.kind + " " + pct(s.total) + "%");
    }

    private void notifyStaff(String head, Score s, String extra) {
        for (Player st : Bukkit.getOnlinePlayers()) {
            if (!st.hasPermission("minesmp.staff.guard")) continue;
            st.sendMessage(Text.c(head));
            for (String l : s.lines) st.sendMessage(Text.c(" &8» " + l));
            if (extra != null) st.sendMessage(Text.c(" &8» " + extra));
        }
        Bukkit.getConsoleSender().sendMessage(Text.c(head));
        for (String l : s.lines) Bukkit.getConsoleSender().sendMessage(Text.c(" » " + l));
        if (extra != null) Bukkit.getConsoleSender().sendMessage(Text.c(" » " + extra));
    }

    // ================================================================ GrimAC einrichten
    /** Traegt den Meldebefehl in plugins/GrimAC/punishments.yml ein (einmalig), damit Grim-Alarme beim Waechter ankommen. */
    private void setupGrim() {
        if (!enabled() || !cfg().getBoolean("grim.enabled", true) || !cfg().getBoolean("grim.auto-setup", true)) return;
        if (Bukkit.getPluginManager().getPlugin("GrimAC") == null) return;
        File f = new File(plugin.getDataFolder().getParentFile(), "GrimAC/punishments.yml");
        Map<String, String> steps = Map.of("Simulation", "50:25", "Knockback", "10:5", "Post", "30:15", "BadPackets", "20:10", "Reach", "5:5",
                "Hitboxes", "5:5", "Misc", "20:10", "Combat", "10:5", "Autoclicker", "20:10");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!f.isFile()) return;
                List<String> in = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                for (String l : in) if (l.contains("guard grim")) return;
                List<String> out = new ArrayList<>();
                String group = "";
                int added = 0;
                for (String l : in) {
                    out.add(l);
                    if (l.matches("^  [A-Za-z0-9_]+:\\s*$")) group = l.trim().replace(":", "");
                    if (l.matches("^    commands:\\s*$")) {
                        out.add("      - \"" + steps.getOrDefault(group, "20:10") + " guard grim %player% %check_name% %vl%\"");
                        added++;
                    }
                }
                if (added == 0) return;
                Files.write(new File(f.getParentFile(), "punishments.yml.minesmp-backup").toPath(), in, StandardCharsets.UTF_8);
                Files.write(f.toPath(), out, StandardCharsets.UTF_8);
                int n = added;
                plugin.sync(() -> {
                    plugin.getLogger().info("Waechter: Meldebefehl in " + n + " GrimAC-Gruppen eingetragen (Sicherung: punishments.yml.minesmp-backup).");
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "grim reload");
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Waechter: GrimAC konnte nicht eingerichtet werden: " + e);
            }
        });
    }

    // ================================================================ Befehle
    private void command(CommandSender s, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "grim" -> {
                if (!(s instanceof Player) && a.length >= 4) grimReport(a[1], a[2], a[3]); // nur Konsole (GrimAC), nie Spieler
            }
            case "check" -> {
                Player t = a.length < 2 ? null : Bukkit.getPlayerExact(a[1]);
                if (t == null) { Msg.send(s, "guard.offline", "{prefix}&cDieser Spieler ist nicht online."); return; }
                Score sc = score(t, track(t));
                s.sendMessage(Text.c("&b&lKI-System &8┃ &f" + t.getName() + " &7Gesamt: &e" + pct(sc.total) + "% &8(Warnung ab " + Math.round(alertAt()) + ", Bann ab " + Math.round(banAt()) + ")"));
                Track tr = track(t);
                s.sendMessage(Text.c(" &8» &7Abgebaut unter Tage: &f" + tr.fillerOver + " &7Oberwelt, &f" + tr.fillerNether + " &7Nether"));
                if (sc.lines.isEmpty()) s.sendMessage(Text.c(" &8» &aKeine Auffaelligkeiten."));
                for (String l : sc.lines) s.sendMessage(Text.c(" &8» " + l));
                if (t.hasPermission("minesmp.guard.bypass")) s.sendMessage(Text.c(" &8» &7Dieser Spieler ist vom Waechter ausgenommen."));
            }
            case "cases", "faelle" -> cases(s, a.length >= 2 ? a[1] : null);
            case "case", "fall" -> { if (a.length >= 2) caseInfo(s, a[1].replace("#", "").toUpperCase(Locale.ROOT)); }
            case "reset" -> {
                if (!s.hasPermission("minesmp.admin.guard")) { Msg.send(s, "no-permission", "{prefix}&cDazu hast du keine Berechtigung."); return; }
                Player t = a.length < 2 ? null : Bukkit.getPlayerExact(a[1]);
                if (t == null) { Msg.send(s, "guard.offline", "{prefix}&cDieser Spieler ist nicht online."); return; }
                tracks.remove(t.getUniqueId());
                PlayerData d = plugin.data().get(t);
                if (d != null) d.guard = "";
                plugin.audit().log(s, "GUARD_RESET", t.getName());
                Msg.send(s, "guard.reset", "{prefix}&aZaehler des Waechters fuer &f{name} &ageleert.", "name", t.getName());
            }
            case "aitest", "kitest" -> {
                if (!s.hasPermission("minesmp.admin.guard")) { Msg.send(s, "no-permission", "{prefix}&cDazu hast du keine Berechtigung."); return; }
                if (!aiOn()) { Msg.send(s, "guard.ai-off", "{prefix}&7Die KI-Gegenpruefung ist aus. &8(config.yml -> guard.ai.enabled und api-key)"); return; }
                Msg.send(s, "guard.ai-testing", "{prefix}&7Frage die KI mit einem Beispiel-Fall ...");
                String key = cfg().getString("ai.api-key", ""), model = cfg().getString("ai.model", "claude-opus-5-5"), url = cfg().getString("ai.base-url", "");
                String sample = "{\"player\":\"anonym\",\"playtime_hours\":6.5,\"bedrock_client\":false,\"xray\":{\"diamond\":{\"hidden_veins\":31,\"blocks_mined\":600,"
                        + "\"veins_per_1000\":51.7,\"legit_upper_bound_per_1000\":20.0,\"p_value\":2.1e-06,\"median_gap\":17}},\"wall_access\":null,\"grim\":[],"
                        + "\"local_confidence\":99.9,\"suspected\":\"X-Ray\"}";
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    GuardAi.Result r;
                    try { r = new GuardAi(key, model, url).review(sample); } catch (Throwable ex) { r = new GuardAi.Result(null, "KI-Bibliothek nicht verfuegbar: " + ex); }
                    GuardAi.Result res = r;
                    plugin.sync(() -> {
                        if (res.verdict() == null) Msg.send(s, "guard.ai-fail", "{prefix}&cKI-Test fehlgeschlagen: &f{error}", "error", String.valueOf(res.error()));
                        else Msg.send(s, "guard.ai-ok", "{prefix}&aKI erreichbar. &7Urteil zum Beispiel: &f{verdict} &8({conf}%) &7{reason}", "verdict", res.verdict().cheating ? "Cheater" : "kein Cheater",
                                "conf", String.valueOf(res.verdict().confidence), "reason", res.verdict().reason);
                    });
                });
            }
            default -> {
                s.sendMessage(Text.c("&b&lKI-System &8┃ &7Waechter: " + (enabled() ? "&aan" : "&caus") + " &8| &7Aktion: &f" + (banMode() ? "permanent bannen" : "nur warnen")
                        + " &8| &7Warnung ab &e" + Math.round(alertAt()) + "% &7Bann ab &e" + Math.round(banAt()) + "%"));
                s.sendMessage(Text.c(" &8» &7X-Ray: " + onOff("xray.enabled") + " &8| &7Durch Waende: " + onOff("freecam.enabled") + " &8| &7GrimAC: "
                        + (Bukkit.getPluginManager().getPlugin("GrimAC") == null ? "&cnicht installiert" : onOff("grim.enabled")) + " &8| &7KI-Gegenpruefung: " + (aiOn() ? "&aan" : "&7aus")));
                s.sendMessage(Text.c(" &8» &b/guard check <Spieler> &8| &b/guard cases [Spieler] &8| &b/guard case <Nr> &8| &b/guard reset <Spieler> &8| &b/guard aitest"));
            }
        }
    }

    private String onOff(String path) { return cfg().getBoolean(path, true) ? "&aan" : "&caus"; }

    private List<String> complete(CommandSender s, String[] a) {
        if (a.length == 1) return List.of("check", "cases", "case", "reset", "aitest");
        return a.length == 2 && List.of("check", "cases", "reset").contains(a[0].toLowerCase(Locale.ROOT)) ? null : List.of();
    }

    private void cases(CommandSender s, String name) {
        plugin.db().queryThen(c -> {
            List<String> out = new ArrayList<>();
            String sql = "SELECT id, time, name, kind, confidence, action FROM guard_cases " + (name == null ? "" : "WHERE LOWER(name) = ? ") + "ORDER BY time DESC LIMIT 10";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (name != null) ps.setString(1, name.toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    SimpleDateFormat df = new SimpleDateFormat("dd.MM. HH:mm");
                    while (rs.next()) out.add("&8#&f" + rs.getString(1) + " &7" + df.format(new Date(rs.getLong(2))) + " &f" + rs.getString(3) + " &7" + rs.getString(4)
                            + " &e" + pct(rs.getDouble(5)) + "% " + ("BAN".equals(rs.getString(6)) ? "&cgebannt" : "&enicht gebannt (KI widersprach)"));
                }
            }
            return out;
        }, out -> {
            s.sendMessage(Text.c("&b&lKI-System &8┃ &7Letzte Faelle" + (name == null ? "" : " von &f" + name) + "&7:"));
            if (out.isEmpty()) s.sendMessage(Text.c(" &8» &7Keine Faelle."));
            for (String l : out) s.sendMessage(Text.c(" &8» " + l));
        });
    }

    private void caseInfo(CommandSender s, String id) {
        plugin.db().queryThen(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name, kind, confidence, action, evidence, ai, time FROM guard_cases WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    List<String> out = new ArrayList<>();
                    out.add("&b&lKI-System &8┃ &7Fall &f#" + id + " &8- &f" + rs.getString(1) + " &7" + rs.getString(2) + " &e" + pct(rs.getDouble(3)) + "% &7"
                            + new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(rs.getLong(7))) + " &8(" + rs.getString(4) + ")");
                    for (String l : rs.getString(5).split("\n")) if (!l.isBlank()) out.add(" &8» " + l);
                    if (rs.getString(6) != null && !rs.getString(6).isBlank()) out.add(" &8» &d" + rs.getString(6));
                    return out;
                }
            }
        }, out -> {
            if (out == null) { Msg.send(s, "guard.no-case", "{prefix}&cDiesen Fall gibt es nicht."); return; }
            for (String l : out) s.sendMessage(Text.c(l));
        });
    }

    /** Fuer Tests und andere Module: aktuelle Gesamt-Sicherheit eines Spielers. */
    public double confidenceOf(Player p) {
        return score(p, track(p)).total;
    }
}
