package de.minesmp.core.events;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.economy.EconomyService;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import de.minesmp.core.utility.RewardService;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Goldrausch: ein Team-Mitglied (ab SrMod, Recht minesmp.goldrausch.start) startet ihn mit /sgoldrausch, dann laeuft er
 * {@code days} Tage (Standard 14). Waehrenddessen kann in jedem natuerlichen Erz ein Fund stecken:
 * <ul>
 *   <li>Goldnugget und Goldbarren: Geld direkt aufs Konto.</li>
 *   <li>Star-Tool und Spawner: ein zufaelliges Item.</li>
 *   <li>Goldticket (Hauptgewinn): 14 Tage lang jeden Tag Gratis-Schluessel fuer jede Kiste, 30 Tage Premium (oder ein
 *       Gutschein zum Verschenken), goldener Name und goldene Aura. Hoechstens eins am Tag, mit Tages-Garantie.</li>
 * </ul>
 * Jedes Erz gibt ausserdem Goldstaub (Shop mit eigenen Items, Meilensteine). Glueck erhoeht die Fund-Chancen:
 * Goldfieber (zuegig weiter abbauen), Goldene Stunde, Server-Ziel und die Goldrausch-Items (Faehigkeiten GOLD_*).
 * Am Ende gewinnen die Top 10 der Rangliste (gewonnenes Geld). Menue: {@link GoldrushMenu}, Einstellungen: goldrausch.yml.
 * Von Spielern gesetzte Erze zaehlen nicht (Markierung aus dem Skill-System). Spielerwerte liegen in den Statistiken
 * (gr_*), die Rangliste in der Tabelle goldrush, der Stand im Schluessel "goldrausch".
 */
public final class GoldrushService implements Listener {
    public enum Find {
        NUGGET("nugget", "Goldnugget", "goldnugget", null),
        BAR("bar", "Goldbarren", "goldbarren", "gr_bar"),
        STARTOOL("startool", "Star-Tool", null, "gr_tool"),
        SPAWNER("spawner", "Spawner", null, "gr_spawner"),
        TICKET("ticket", "Goldticket", "goldticket", "gr_ticket");

        public final String id, label, item, title;

        Find(String id, String label, String item, String title) {
            this.id = id; this.label = label; this.item = item; this.title = title;
        }
    }

    public record Milestone(int ores, String name, ConfigurationSection reward) {}
    public record ShopEntry(String item, int price, int amount) {}
    public record Top(String uuid, String name, double money, long ores) {}

    private static final class Session {
        int streak, texts;
        long last, lastThrow, panUntil;
        BossBar bar;
    }

    private static final String[] KEYS = {"gr_cents", "gr_ores", "gr_dust", "gr_finds", "gr_claimed", "gr_best", "gr_today", "gr_day",
            "gr_n_nugget", "gr_n_bar", "gr_n_startool", "gr_n_spawner", "gr_n_ticket"};
    public static final List<Material> ORE_ORDER = List.of(Material.COAL_ORE, Material.COPPER_ORE, Material.IRON_ORE, Material.GOLD_ORE,
            Material.REDSTONE_ORE, Material.LAPIS_ORE, Material.EMERALD_ORE, Material.DIAMOND_ORE, Material.ANCIENT_DEBRIS,
            Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE);
    private static final Map<Material, String> ORE_NAMES = Map.ofEntries(
            Map.entry(Material.COAL_ORE, "Steinkohle"), Map.entry(Material.COPPER_ORE, "Kupfererz"), Map.entry(Material.IRON_ORE, "Eisenerz"),
            Map.entry(Material.REDSTONE_ORE, "Redstone-Erz"), Map.entry(Material.LAPIS_ORE, "Lapislazulierz"),
            Map.entry(Material.NETHER_QUARTZ_ORE, "Netherquarzerz"), Map.entry(Material.GOLD_ORE, "Golderz"),
            Map.entry(Material.DIAMOND_ORE, "Diamanterz"), Map.entry(Material.EMERALD_ORE, "Smaragderz"),
            Map.entry(Material.NETHER_GOLD_ORE, "Nethergolderz"), Map.entry(Material.ANCIENT_DEBRIS, "Antiker Schrott"));

    private final MineSMP plugin;
    private final NamespacedKey dynKey, fwKey;
    private final Map<Material, Integer> dustPer = new EnumMap<>(Material.class);
    private final Map<Material, Material> baseOf = new EnumMap<>(Material.class);
    private final List<Milestone> milestones = new ArrayList<>();
    private final List<ShopEntry> shop = new ArrayList<>();
    private final TreeMap<Long, Double> community = new TreeMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private final Set<UUID> helmEffect = new HashSet<>();
    private final Map<UUID, Long> stopConfirm = new HashMap<>();
    private YamlConfiguration cfg = new YamlConfiguration();
    private YamlConfiguration titleGlyphs;
    private volatile List<Top> top = List.of();
    private boolean goldenNameOn = true, auraOn = true;

    // Stand (bleibt ueber Neustarts erhalten)
    private long season, start, end, totalOres, salt, ticketDay;
    private int ticketsTotal, ticketsToday;
    private boolean ended = true;
    private String starter = "";
    private List<String> winners = new ArrayList<>();
    private List<String> finders = new ArrayList<>();     // "Name:Zeit", neueste zuerst

    private boolean goldenHour;
    private double communityBonus;
    private long lastLeft = Long.MAX_VALUE;
    private int flushCount;

    public GoldrushService(MineSMP plugin) {
        this.plugin = plugin;
        this.dynKey = new NamespacedKey(plugin, "gr_dynamit");
        this.fwKey = new NamespacedKey(plugin, "no_damage");
        reload();
        loadState();
        communityBonus = bonusFor(totalOres);
        goldenHour = active() && goldenHourNow();
        plugin.listen(this);
        plugin.command(new SimpleCommand("goldrausch", "minesmp.goldrausch", "Goldrausch: Funde, Goldticket, Shop, Rangliste",
                (s, l, a) -> command(s, a), (s, a) -> a.length == 1 ? List.of("info", "ticket", "erze", "funde", "mein", "shop", "ziele", "top") : List.of(), "goldrush", "gr"));
        plugin.command(new SimpleCommand("sgoldrausch", "minesmp.goldrausch.start", "Goldrausch starten (ab SrMod)",
                (s, l, a) -> staffCommand(s, a), (s, a) -> a.length == 1 ? (s.hasPermission("minesmp.admin.goldrausch")
                        ? List.of("start", "stop", "status", "abbrechen", "zeit", "staub", "fund", "reload") : List.of("start", "stop", "status"))
                        : a.length == 2 && List.of("staub", "fund").contains(a[0].toLowerCase(Locale.ROOT)) ? null
                        : a.length == 3 && a[0].equalsIgnoreCase("fund") ? List.of("nugget", "bar", "startool", "spawner", "ticket") : List.of(), "startgoldrausch"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::second, 40, 20);
        Bukkit.getScheduler().runTaskTimer(plugin, this::barTick, 40, 4);
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushTick, 300, 300);
        Bukkit.getScheduler().runTaskTimer(plugin, this::helmTick, 60, 40);
        Bukkit.getScheduler().runTaskLater(plugin, this::setupLuckPerms, 100);
        if (season > 0) refreshTop();
    }

    // ================================================================ Einstellungen und Stand

    public void reload() {
        cfg = plugin.yaml("goldrausch.yml");
        dustPer.clear(); baseOf.clear(); milestones.clear(); shop.clear(); community.clear();
        ConfigurationSection os = cfg.getConfigurationSection("ores");
        if (os != null) for (String k : os.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m == null) { plugin.getLogger().warning("goldrausch.yml: unbekanntes Erz " + k); continue; }
            dustPer.put(m, Math.max(0, os.getInt(k)));
            baseOf.put(m, m);
            Material ds = Material.matchMaterial("DEEPSLATE_" + m.name());
            if (ds != null) { dustPer.put(ds, Math.max(0, os.getInt(k))); baseOf.put(ds, m); }
        }
        for (Map<?, ?> m : cfg.getMapList("milestones")) {
            YamlConfiguration r = new YamlConfiguration();
            if (m.get("reward") instanceof Map<?, ?> rm) fill(r, rm);
            milestones.add(new Milestone(m.get("ores") instanceof Number n ? n.intValue() : 0, String.valueOf(m.get("name")), r));
        }
        milestones.sort(java.util.Comparator.comparingInt(Milestone::ores));
        for (Map<?, ?> m : cfg.getMapList("shop")) {
            String item = String.valueOf(m.get("item")).toLowerCase(Locale.ROOT);
            int price = m.get("price") instanceof Number n ? n.intValue() : 0;
            int amount = m.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1;
            if (price > 0) shop.add(new ShopEntry(item, price, amount));
        }
        ConfigurationSection cs = cfg.getConfigurationSection("community");
        if (cs != null) for (String k : cs.getKeys(false)) {
            try { community.put(Long.parseLong(k), cs.getDouble(k)); } catch (NumberFormatException ignored) { }
        }
        goldenNameOn = cfg.getBoolean("ticket.golden-name", true);
        auraOn = cfg.getBoolean("ticket.aura", true);
    }

    private static void fill(ConfigurationSection s, Map<?, ?> m) {
        for (var e : m.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> sub) fill(s.createSection(String.valueOf(e.getKey())), sub);
            else s.set(String.valueOf(e.getKey()), e.getValue());
        }
    }

    private void loadState() {
        try {
            String raw = plugin.db().kvGet("goldrausch").get(3, TimeUnit.SECONDS);
            if (raw == null || raw.isBlank()) return;
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(raw);
            season = y.getLong("season");
            start = y.getLong("start");
            end = y.getLong("end");
            totalOres = y.getLong("total-ores");
            ended = y.getBoolean("ended", true);
            starter = y.getString("starter", "");
            salt = y.getLong("salt");
            ticketsTotal = y.getInt("tickets-total");
            ticketsToday = y.getInt("tickets-today");
            ticketDay = y.getLong("ticket-day");
            winners = new ArrayList<>(y.getStringList("winners"));
            finders = new ArrayList<>(y.getStringList("finders"));
        } catch (Exception e) {
            plugin.getLogger().warning("Goldrausch: Stand nicht lesbar - " + e.getMessage());
        }
    }

    private void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("season", season);
        y.set("start", start);
        y.set("end", end);
        y.set("total-ores", totalOres);
        y.set("ended", ended);
        y.set("starter", starter);
        y.set("salt", salt);
        y.set("tickets-total", ticketsTotal);
        y.set("tickets-today", ticketsToday);
        y.set("ticket-day", ticketDay);
        y.set("winners", winners);
        y.set("finders", finders);
        plugin.db().kvSet("goldrausch", y.saveToString());
    }

    public boolean active() { return !ended && System.currentTimeMillis() < end; }
    public long season() { return season; }
    public long left() { return Math.max(0, end - System.currentTimeMillis()); }
    public boolean everRan() { return season > 0; }
    public long totalOres() { return totalOres; }
    public String starter() { return starter; }
    public List<Milestone> milestones() { return milestones; }
    public List<ShopEntry> shopEntries() { return shop; }
    public TreeMap<Long, Double> communityTiers() { return community; }
    public double communityBonus() { return communityBonus; }
    public boolean goldenHour() { return goldenHour; }
    public YamlConfiguration cfg() { return cfg; }
    public MineSMP plugin() { return plugin; }
    public int dustFor(Material m) { return dustPer.getOrDefault(m, 0); }
    public boolean isOre(Material m) { return dustPer.containsKey(m); }
    public static String oreName(Material m) { return ORE_NAMES.getOrDefault(m, Text.capitalize(m.name())); }
    public int ticketsTotal() { return ticketsTotal; }
    public int ticketsToday() { rollDay(); return ticketsToday; }
    /** Letzte Goldticket-Finder als "Name:Zeit" (neueste zuerst). */
    public List<String> finders() { return finders; }

    /** Text fuer Anzeigen: Restzeit, "Beendet" oder "Pause". */
    public String leftText() {
        if (active()) return shortSpan(left());
        return season > 0 ? "Beendet" : "Pause";
    }

    public static String shortSpan(long l) {
        if (l >= 86_400_000L) return (l / 86_400_000L) + "d " + (l % 86_400_000L / 3_600_000L) + "h";
        if (l >= 3_600_000L) return (l / 3_600_000L) + "h " + (l % 3_600_000L / 60_000L) + "m";
        return (l / 60_000L) + "m " + (l % 60_000L / 1000L) + "s";
    }

    private static long today() { return LocalDate.now(RewardService.ZONE).toEpochDay(); }

    // ================================================================ Spielerwerte

    /** Werte des laufenden (oder zuletzt beendeten) Goldrauschs; bei einer neuen Runde beginnt alles bei null. */
    public PlayerData data(Player p) {
        PlayerData d = plugin.data().get(p);
        if (d.stat("gr_season") != season) {
            for (String k : KEYS) d.stats.remove(k);
            d.stats.put("gr_season", season);
            d.dirty = true;
        }
        return d;
    }

    public double won(Player p) { return data(p).stat("gr_cents") / 100.0; }
    public long ores(Player p) { return data(p).stat("gr_ores"); }
    public long dust(Player p) { return data(p).stat("gr_dust"); }
    public long finds(Player p) { return data(p).stat("gr_finds"); }
    public long found(Player p, Find f) { return data(p).stat("gr_n_" + f.id); }
    public boolean claimed(Player p, int index) { return (data(p).stat("gr_claimed") & (1L << index)) != 0; }

    /** Heute im Goldrausch gewonnenes Geld. */
    public double wonToday(Player p) {
        PlayerData d = data(p);
        return d.stat("gr_day") == today() ? d.stat("gr_today") / 100.0 : 0;
    }

    public void addDust(Player p, long n) {
        PlayerData d = data(p);
        d.stats.put("gr_dust", Math.max(0, d.stat("gr_dust") + n));
        d.dirty = true;
    }

    private Session session(Player p) { return sessions.computeIfAbsent(p.getUniqueId(), k -> new Session()); }

    private String ability(ItemStack it) {
        String a = plugin.customItems() == null ? null : plugin.customItems().abilityOf(it);
        return a == null ? "" : a;
    }

    public boolean elixirActive(Player p) { return plugin.data().get(p).stat("gr_elixir_until") > System.currentTimeMillis(); }
    public long elixirLeft(Player p) { return Math.max(0, plugin.data().get(p).stat("gr_elixir_until") - System.currentTimeMillis()); }

    // ---------------------------------------------------------------- Goldticket

    public long ticketUntil(Player p) {
        PlayerData d = plugin.data().getIfLoaded(p.getUniqueId());
        return d == null ? 0 : d.stat("gr_ticket_until");
    }

    public boolean hasTicket(Player p) { return ticketUntil(p) > System.currentTimeMillis(); }
    /** Fuer das Rang-System: goldener, schimmernder Name. */
    public boolean goldenName(Player p) { return goldenNameOn && hasTicket(p); }
    /** Fuer das Rang-System: goldene Funken um den Spieler. */
    public boolean goldenAura(Player p) { return auraOn && hasTicket(p); }
    public boolean dailyReady(Player p) { return hasTicket(p) && plugin.data().get(p).stat("gr_ticket_day") != today(); }

    /** Tages-Schluessel des Goldtickets abholen (einmal am Tag). */
    public boolean claimDaily(Player p) {
        if (!dailyReady(p)) return false;
        PlayerData d = plugin.data().get(p);
        d.stats.put("gr_ticket_day", today());
        d.dirty = true;
        List<String> parts = new ArrayList<>();
        ConfigurationSection keys = cfg.getConfigurationSection("ticket.daily-keys");
        if (keys != null && plugin.crates() != null) for (String k : keys.getKeys(false)) {
            int n = keys.getInt(k);
            if (n <= 0) continue;
            plugin.crates().giveKeys(p.getUniqueId(), k, n);
            parts.add("&e" + n + "x " + plugin.crates().displayName(k));
        }
        Msg.send(p, "goldrush.ticket-daily", "{prefix}&6Goldticket: &7deine Gratis-Schluessel fuer heute: {list}&7. Oeffnen: &f/keys", "list", String.join("&7, ", parts));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.4f);
        plugin.audit().log(p.getName(), "GOLDTICKET_TAG", Text.strip(String.join(", ", parts)));
        return true;
    }

    public List<String> dailyKeyLines() {
        List<String> out = new ArrayList<>();
        ConfigurationSection keys = cfg.getConfigurationSection("ticket.daily-keys");
        if (keys != null && plugin.crates() != null) for (String k : keys.getKeys(false))
            if (keys.getInt(k) > 0) out.add("&e" + keys.getInt(k) + "x " + plugin.crates().displayName(k) + "-Schluessel");
        return out;
    }

    private void rollDay() {
        long t = today();
        if (t != ticketDay) { ticketDay = t; ticketsToday = 0; }
    }

    private static int minutes(String hm, int def) {
        try {
            String[] p = hm.split(":");
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** Tages-Garantie: ab der geheimen Uhrzeit des Tages steckt das Ticket im naechsten Erz (wenn heute noch keins fiel). */
    private boolean guaranteeDue() {
        if (!cfg.getBoolean("ticket.guarantee.enabled", true) || ticketsToday > 0) return false;
        int from = minutes(cfg.getString("ticket.guarantee.from", "17:00"), 17 * 60), to = minutes(cfg.getString("ticket.guarantee.to", "22:00"), 22 * 60);
        if (to <= from) to = from + 1;
        long day = today();
        int secret = from + new java.util.Random(salt ^ (season * 1_000_003L + day * 7919L)).nextInt(to - from);
        LocalTime t = LocalTime.now(RewardService.ZONE);
        int now = t.getHour() * 60 + t.getMinute();
        if (now < secret) return false;
        // Am Starttag nur, wenn der Goldrausch vor der geheimen Uhrzeit begonnen hat
        java.time.ZonedDateTime st = java.time.Instant.ofEpochMilli(start).atZone(RewardService.ZONE);
        return st.toLocalDate().toEpochDay() != day || st.getHour() * 60 + st.getMinute() < secret;
    }

    private boolean ticketPossible(Player p, PlayerData d) {
        if (hasTicket(p) || d.stat("gr_ores") < cfg.getInt("ticket.min-ores", 150)) return false;
        rollDay();
        return ticketsToday < Math.max(0, cfg.getInt("ticket.max-per-day", 1)) && ticketsTotal < Math.max(0, cfg.getInt("ticket.max-total", 14));
    }

    // ---------------------------------------------------------------- Glueck

    /** Aktueller Gluecks-Faktor des Spielers (Goldfieber, Items, Server-Ziel, Elixier, Goldene Stunde). */
    public double luck(Player p) {
        Session s = sessions.get(p.getUniqueId());
        int streak = s == null || System.currentTimeMillis() - s.last > feverWindow() ? 0 : s.streak;
        return luck(p, feverBonus(streak));
    }

    private double luck(Player p, double fever) {
        PlayerInventory inv = p.getInventory();
        double add = fever + communityBonus;
        if (ability(inv.getItemInMainHand()).equals("GOLD_PICKE")) add += cfg.getDouble("items.pick-bonus", 0.20);
        if (ability(inv.getHelmet()).equals("GOLD_HELM")) add += cfg.getDouble("items.helmet-bonus", 0.10);
        double m = 1 + add;
        if (elixirActive(p)) m *= cfg.getDouble("items.elixir-multiplier", 1.5);
        if (goldenHour) m *= cfg.getDouble("golden-hour.multiplier", 2.0);
        return m;
    }

    private long feverWindow() { return Math.max(3, cfg.getInt("fever.window-seconds", 12)) * 1000L; }

    private double feverBonus(int streak) {
        return Math.min(cfg.getDouble("fever.max", 0.5), Math.max(0, streak - 1) * cfg.getDouble("fever.per-ore", 0.01));
    }

    /** Aktuelles Goldfieber des Spielers als Anteil (0.12 = +12 %). */
    public double fever(Player p) {
        Session s = sessions.get(p.getUniqueId());
        return s == null || System.currentTimeMillis() - s.last > feverWindow() ? 0 : feverBonus(s.streak);
    }

    public double chance(Find f) { return Math.max(0, cfg.getDouble((f == Find.TICKET ? "ticket" : "finds." + f.id) + ".chance", 0)); }

    private boolean worldOk(World w) {
        List<String> only = cfg.getStringList("worlds");
        if (!only.isEmpty()) return only.contains(w.getName());
        return plugin.spawn() == null || !plugin.spawn().isSpawnWorld(w);
    }

    // ================================================================ Erze und Funde

    /** Aufruf aus dem Skill-System bei jedem abgebauten Erz im Ueberlebensmodus (placed = vom Spieler gesetzt). */
    public void onOre(Player p, Block b, boolean placed) {
        if (placed || !active()) return;
        Material type = b.getType();
        Integer dust = dustPer.get(type);
        if (dust == null || !worldOk(b.getWorld())) return;
        PlayerData d = data(p);
        Session s = session(p);
        long now = System.currentTimeMillis();
        if (now - s.last > feverWindow()) s.streak = 0;
        s.streak++;
        s.last = now;
        double luck = luck(p, feverBonus(s.streak));
        d.addStat("gr_ores", 1);
        if (dust > 0) d.stats.merge("gr_dust", (long) dust, Long::sum);
        long best = Math.round(luck * 100);
        if (best > d.stat("gr_best")) d.stats.put("gr_best", best);
        dirty.add(p.getUniqueId());
        long before = totalOres++;
        double nb = bonusFor(totalOres);
        if (nb > communityBonus) {
            communityBonus = nb;
            Long tier = community.floorKey(totalOres);
            Msg.broadcast("goldrush.community", "{prefix}&b&lSERVER-ZIEL! &7Gemeinsam &f{ores} Erze &7abgebaut - ab jetzt &a+{bonus} % Glueck &7fuer alle.",
                    "ores", Text.money(tier == null ? before + 1 : tier), "bonus", pct(nb));
            for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.3f);
        }
        long oresNow = d.stat("gr_ores");
        for (Milestone m : milestones) {
            if (m.ores() != oresNow) continue;
            Msg.send(p, "goldrush.milestone-reached", "{prefix}&6&lMEILENSTEIN! &f{name} &7({ores} Erze) erreicht. Belohnung abholen: &b/goldrausch ziele",
                    "name", m.name(), "ores", Text.money(oresNow));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.2f);
        }
        hud(p, b, s, dust, luck);
        // Funde: hoechstens einer je Erz, der seltenste zuerst. Das Goldticket haengt nicht vom Glueck ab.
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (ticketPossible(p, d) && (guaranteeDue() || r.nextDouble() < chance(Find.TICKET))) { grant(p, b.getLocation().add(0.5, 0.5, 0.5), Find.TICKET, "Erz"); return; }
        double barLuck = luck * (ability(p.getInventory().getItemInOffHand()).equals("GOLD_HUFEISEN") ? cfg.getDouble("items.horseshoe-factor", 2.0) : 1);
        Find f = r.nextDouble() < chance(Find.STARTOOL) * luck ? Find.STARTOOL
                : r.nextDouble() < chance(Find.SPAWNER) * luck ? Find.SPAWNER
                : r.nextDouble() < chance(Find.BAR) * barLuck ? Find.BAR
                : r.nextDouble() < chance(Find.NUGGET) * luck ? Find.NUGGET : null;
        if (f != null) grant(p, b.getLocation().add(0.5, 0.5, 0.5), f, "Erz");
    }

    private double bonusFor(long ores) {
        var e = community.floorEntry(ores);
        return e == null ? 0 : e.getValue();
    }

    private static String pct(double v) {
        double x = v * 100;
        return Math.abs(x - Math.round(x)) < 0.05 ? String.valueOf(Math.round(x)) : String.format(Locale.GERMANY, "%.1f", x);
    }

    public static String factor(double m) { return "x" + String.format(Locale.US, "%.2f", m); }

    private static int[] range(ConfigurationSection c, String path, int a, int b) {
        if (c.isList(path)) {
            List<Integer> l = c.getIntegerList(path);
            if (l.size() == 2) return new int[]{l.get(0), Math.max(l.get(0), l.get(1))};
        } else if (c.isSet(path)) {
            int v = c.getInt(path);
            return new int[]{v, v};
        }
        return new int[]{a, b};
    }

    private static int roll(int[] r) { return r[0] >= r[1] ? r[0] : ThreadLocalRandom.current().nextInt(r[0], r[1] + 1); }

    /** Text fuers Menue: Betrag oder Spanne eines Geld-Fundes. */
    public String moneyText(Find f) {
        int[] r = range(cfg, "finds." + f.id + ".money", 0, 0);
        return r[0] == r[1] ? plugin.eco().format(r[0]) : Text.money(r[0]) + " - " + plugin.eco().format(r[1]);
    }

    private void addMoney(Player p, PlayerData d, double money, String what) {
        plugin.eco().deposit(p, money, "GOLDRAUSCH", what);
        long cents = Math.round(money * 100);
        d.stats.merge("gr_cents", cents, Long::sum);
        long t = today();
        if (d.stat("gr_day") != t) { d.stats.put("gr_day", t); d.stats.put("gr_today", 0L); }
        d.stats.merge("gr_today", cents, Long::sum);
    }

    /** Vergibt einen Fund mit allem Drumherum (auch fuer /sgoldrausch fund und die Goldpfanne). */
    public void grant(Player p, Location at, Find f, String source) {
        PlayerData d = data(p);
        d.addStat("gr_finds", 1);
        d.addStat("gr_n_" + f.id, 1);
        dirty.add(p.getUniqueId());
        World w = at.getWorld();
        switch (f) {
            case NUGGET -> {
                double money = roll(range(cfg, "finds.nugget.money", 4000, 6000));
                addMoney(p, d, money, "Goldnugget");
                if (cfg.getBoolean("effects.item-animation", true) && cfg.getBoolean("effects.nugget-animation", true)) itemAnimation(p, plugin.customItems().create("goldnugget"));
                popup(p, at, "nugget", "+" + plugin.eco().format(money), 1f);
                w.spawnParticle(Particle.WAX_ON, at, 14, 0.4, 0.4, 0.4, 0.06);
                if (cfg.getBoolean("effects.sounds", true)) {
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 0.7f);
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.5f);
                }
                todayBar(p);
            }
            case BAR -> {
                double money = roll(range(cfg, "finds.bar.money", 250000, 250000));
                addMoney(p, d, money, "Goldbarren");
                bigEffects(p, at, f, plugin.customItems().create("goldbarren"), "<g:#FFF3B0:#FFC83D>&lGOLDBARREN!</g>", null, money);
                popup(p, at, "barren", "+" + plugin.eco().format(money), 1.3f);
                Msg.send(p, "goldrush.find-bar", "{prefix}&6&lGOLDBARREN! &a+{money} &7direkt auf dein Konto.", "money", plugin.eco().format(money));
                if (cfg.getBoolean("finds.bar.broadcast", true))
                    broadcastOthers(p, "goldrush.find-bar-all", "{prefix}&6&lGOLDRAUSCH &8» &f{player} &7hat einen &6Goldbarren &7gefunden: &a+{money}", "player", p.getName(), "money", plugin.eco().format(money));
                todayBar(p);
            }
            case STARTOOL -> {
                List<String> pool = cfg.getStringList("finds.startool.items");
                ItemStack it = null;
                for (int i = 0; i < 8 && it == null && !pool.isEmpty(); i++) it = plugin.customItems().create(pool.get(ThreadLocalRandom.current().nextInt(pool.size())));
                if (it == null) { grant(p, at, Find.NUGGET, source); return; }
                String name = Text.strip(Items.name(it));
                Items.giveOrDrop(p, it.clone());
                bigEffects(p, at, f, it, "<g:#FFE66B:#FFB13B>&lSTAR-TOOL!</g>", "&e" + name, 0);
                Msg.send(p, "goldrush.find-tool", "{prefix}&e&lSTAR-TOOL! &7Du hast &f{item} &7gefunden - es liegt in deinem Inventar.", "item", name);
                if (cfg.getBoolean("finds.startool.broadcast", true))
                    broadcastOthers(p, "goldrush.find-tool-all", "{prefix}&6&lGOLDRAUSCH &8» &f{player} &7hat ein &eStar-Tool &7gefunden: &f{item}", "player", p.getName(), "item", name);
            }
            case SPAWNER -> {
                List<String> types = cfg.getStringList("finds.spawner.types");
                ItemStack it = null;
                for (int i = 0; i < 8 && it == null && !types.isEmpty() && plugin.spawners() != null; i++) it = plugin.spawners().item(types.get(ThreadLocalRandom.current().nextInt(types.size())), 1);
                if (it == null) { grant(p, at, Find.NUGGET, source); return; }
                String name = Text.strip(Items.name(it));
                Items.giveOrDrop(p, it.clone());
                bigEffects(p, at, f, it, "<g:#D8B4FE:#9333EA>&lSPAWNER!</g>", "&d" + name, 0);
                Msg.send(p, "goldrush.find-spawner", "{prefix}&d&lSPAWNER! &7Du hast einen &f{item} &7gefunden - er liegt in deinem Inventar.", "item", name);
                if (cfg.getBoolean("finds.spawner.broadcast", true))
                    broadcastOthers(p, "goldrush.find-spawner-all", "{prefix}&6&lGOLDRAUSCH &8» &f{player} &7hat einen &dSpawner &7gefunden: &f{item}", "player", p.getName(), "item", name);
            }
            case TICKET -> ticket(p, at, d, source);
        }
        plugin.audit().log(p.getName(), "GOLDRAUSCH_FUND", f.label + " (" + source + ")");
    }

    private void broadcastOthers(Player finder, String key, String def, String... kv) {
        String s = Msg.get(key, def, kv);
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(finder)) o.sendMessage(Text.c(s));
        Bukkit.getConsoleSender().sendMessage(Text.c(s));
    }

    /** Hauptgewinn: Vorteile setzen, Rang oder Gutschein, Andenken, Ansage fuer alle. */
    private void ticket(Player p, Location at, PlayerData d, String source) {
        long now = System.currentTimeMillis();
        int days = Math.max(1, cfg.getInt("ticket.days", 14));
        long until = Math.max(now, d.stat("gr_ticket_until")) + Duration.ofDays(days).toMillis();
        d.stats.put("gr_ticket_until", until);
        d.stats.remove("gr_ticket_day");
        d.dirty = true;
        rollDay();
        ticketsToday++;
        ticketsTotal++;
        finders.add(0, p.getName() + ":" + now);
        while (finders.size() > 10) finders.remove(finders.size() - 1);
        saveState();
        // Premium: direkt als Rang - oder als Gutschein zum Verschenken, wenn der Finder ihn schon hat
        String rank = cfg.getString("ticket.premium-rank", "premium");
        int pdays = Math.max(0, cfg.getInt("ticket.premium-days", 30));
        boolean voucher = false;
        if (pdays > 0 && !rank.isBlank()) {
            boolean has = p.isPermissionSet("group." + rank) && p.hasPermission("group." + rank);
            if (!has && Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) giveRank(p, rank, pdays);
            else {
                ItemStack v = plugin.customItems().create("premiumgutschein");
                if (v != null) { Items.giveOrDrop(p, v); voucher = true; }
            }
        }
        String date = LocalDate.now(RewardService.ZONE).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        ItemStack keep = plugin.customItems().create("goldticket");
        if (keep != null) {
            keep.editMeta(m -> {
                List<Component> lore = m.hasLore() ? new ArrayList<>(m.lore()) : new ArrayList<>();
                lore.add(Text.item("&8Gefunden von " + p.getName() + " am " + date));
                m.lore(lore);
            });
            Items.giveOrDrop(p, keep.clone());
        }
        bigEffects(p, at, Find.TICKET, keep, "<g:#FFF3B0:#FFC83D:#FF9E2C>&lGOLDTICKET!</g>", "&e&lDER HAUPTGEWINN!", 0);
        Firework fw = p.getWorld().spawn(p.getLocation().add(0, 1, 0), Firework.class, f -> {
            FireworkMeta fm = f.getFireworkMeta();
            fm.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.STAR).withColor(Color.fromRGB(0xFFD86B), Color.fromRGB(0x22C3D6)).withFade(Color.WHITE).flicker(true).trail(true).build());
            fm.setPower(1);
            f.setFireworkMeta(fm);
        });
        fw.getPersistentDataContainer().set(fwKey, PersistentDataType.BYTE, (byte) 1);
        Msg.send(p, "goldrush.ticket-self", "&8&m                                                  \n &6&l✦ GOLDTICKET &7- du hast den Hauptgewinn!\n &8» &f{days} Tage &7jeden Tag Gratis-Schluessel fuer jede Kiste: &b/goldrausch ticket\n &8» {premium}\n &8» &6Goldener Name &7und &6goldene Aura\n&8&m                                                  ",
                "days", String.valueOf(days), "premium", voucher ? "&7Ein &bPremium-Gutschein &7(" + pdays + " Tage) zum Verschenken liegt in deinem Inventar" : "&b" + pdays + " Tage Premium");
        Msg.broadcast("goldrush.ticket-all", "{prefix}&6&l✦ GOLDTICKET! &f{player} &7hat im Goldrausch den &6Hauptgewinn &7gefunden! &8(Nr. {n})", "player", p.getName(), "n", String.valueOf(ticketsTotal));
        announce("GOLDTICKET", "&f&l" + p.getName() + " &ehat den Hauptgewinn gefunden!", 15);
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        plugin.audit().log(p.getName(), "GOLDTICKET", source + ", gilt bis " + until + (voucher ? ", Gutschein" : ""));
        plugin.webhook().send(cfg.getString("webhook-channel", "events"), "Goldticket gefunden", p.getName() + " hat im Goldrausch das Goldticket Nr. " + ticketsTotal + " gefunden.", 0xFFC83D);
        if (plugin.scoreboard() != null) plugin.scoreboard().refreshNametags();
    }

    private void giveRank(Player p, String rank, int days) {
        String cmd = cfg.getString("ticket.rank-command", "lp user {player} parent addtemp {rank} {days}d accumulate");
        if (!cmd.isBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()).replace("{rank}", rank).replace("{days}", String.valueOf(days)));
    }

    // ---------------------------------------------------------------- Effekte

    /** Leiste und Ton bei jedem Erz. */
    private void hud(Player p, Block b, Session s, int dust, double luck) {
        boolean pack = plugin.pack() != null && plugin.pack().has(p);
        if (cfg.getBoolean("effects.sounds", true))
            p.playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.3f, (float) Math.min(1.9, 0.8 + s.streak * 0.02));
        if (!Msg.actionbarBusy(p) || s.streak <= 1) {
            p.sendActionBar(Text.c(Msg.get("goldrush.actionbar", "{icon}&6+{dust} Goldstaub &8| &7Serie &f{streak} &8| &7Glueck &a{luck}",
                    "icon", pack ? plugin.pack().glyphs("&f:staub: ") : "", "dust", String.valueOf(dust), "streak", String.valueOf(s.streak), "luck", factor(luck))));
        }
        if (!cfg.getBoolean("effects.bossbar", true)) return;
        if (s.bar == null) {
            s.bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            p.showBossBar(s.bar);
        }
        String name = "<g:#FFF3B0:#FFC83D>&lGOLDRAUSCH</g> &8| &7Glueck &6&l" + factor(luck) + (goldenHour ? " &8| &e&lGOLDENE STUNDE" : "")
                + (elixirActive(p) ? " &8| &dElixier " + clock(elixirLeft(p)) : "") + " &8| &7Heute &a" + Text.compact(wonToday(p));
        s.bar.name(Text.c(pack ? plugin.pack().glyphs("&f:picke: " + name) : "&6⛏ " + name));
        s.bar.color(goldenHour ? BossBar.Color.PINK : BossBar.Color.YELLOW);
        s.bar.progress(1f);
    }

    private void todayBar(Player p) {
        Msg.actionbar(p, "goldrush.today", "&6Goldrausch heute: &e{money}", "money", plugin.eco().format(wonToday(p)));
    }

    private static String clock(long ms) {
        long sec = ms / 1000;
        return (sec / 60) + ":" + String.format("%02d", sec % 60);
    }

    /** Der Fund fliegt gross ins Bild (Totem-Animation des Clients mit dem Item, das echte Inventar bleibt unberuehrt). */
    private void itemAnimation(Player p, ItemStack item) {
        if (item == null || !cfg.getBoolean("effects.item-animation", true)) return;
        ItemStack show = item.clone();
        show.setAmount(1);
        show.setData(io.papermc.paper.datacomponent.DataComponentTypes.DEATH_PROTECTION, io.papermc.paper.datacomponent.item.DeathProtection.deathProtection().build());
        p.sendEquipmentChange(p, EquipmentSlot.OFF_HAND, show);
        p.sendEntityEffect(org.bukkit.EntityEffect.PROTECTED_FROM_DEATH, p);
        p.stopSound(Sound.ITEM_TOTEM_USE);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) p.updateInventory(); }, 3L);
    }

    /** Kasten ueber dem Erz ("+5.000 MC"), waechst und steigt auf - nur fuer den Finder sichtbar. */
    private void popup(Player p, Location at, String glyph, String text, float size) {
        if (!cfg.getBoolean("effects.popup", true)) return;
        Session s = session(p);
        if (s.texts >= 8) return;
        boolean pack = plugin.pack() != null && plugin.pack().has(p);
        String shown = (pack ? plugin.pack().glyphs("&f:" + glyph + ": ") : "") + "&#FFC83D&l" + text;
        TextDisplay td = at.getWorld().spawn(at.clone().add(0, 0.2, 0), TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setVisibleByDefault(false);
            t.text(Text.c(" " + shown + " "));
            t.setBillboard(Display.Billboard.CENTER);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0xD8, 0x10, 0x0C, 0x04));
            t.setShadowed(false);
            t.setSeeThrough(true);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setViewRange(0.5f);
            t.setTransformation(new Transformation(new Vector3f(0, 0, 0), new Quaternionf(), new Vector3f(size * 0.5f, size * 0.5f, size * 0.5f), new Quaternionf()));
        });
        p.showEntity(plugin, td);
        s.texts++;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!td.isValid()) return;
            td.setInterpolationDelay(0);
            td.setInterpolationDuration(14);
            td.setTransformation(new Transformation(new Vector3f(0, 0.8f, 0), new Quaternionf(), new Vector3f(size * 1.5f, size * 1.5f, size * 1.5f), new Quaternionf()));
        }, 2L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { td.remove(); s.texts = Math.max(0, s.texts - 1); }, 46L);
    }

    private YamlConfiguration titleGlyphs() {
        if (titleGlyphs == null) {
            try (var in = plugin.getResource("signs.yml")) {
                titleGlyphs = in == null ? new YamlConfiguration() : YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException e) { titleGlyphs = new YamlConfiguration(); }
        }
        return titleGlyphs;
    }

    /**
     * Grosser Fund: Item fliegt ins Bild, Schriftzug springt auf (mit Ressourcenpaket als Pixel-Schrift),
     * darunter zaehlt der Betrag hoch (money > 0) oder steht der Untertitel. Dazu Funken und Toene.
     */
    private void bigEffects(Player p, Location at, Find f, ItemStack item, String fallbackTitle, String sub, double money) {
        itemAnimation(p, item);
        World w = at.getWorld();
        w.spawnParticle(Particle.POOF, at, 14, 0.3, 0.3, 0.3, 0.06);
        w.spawnParticle(Particle.WAX_ON, at, 50, 0.7, 0.7, 0.7, 0.2);
        w.spawnParticle(Particle.FIREWORK, at, 24, 0.3, 0.3, 0.3, 0.16);
        if (cfg.getBoolean("effects.sounds", true)) {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, f == Find.TICKET ? 1f : 1.25f);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 0.8f);
        }
        boolean pack = plugin.pack() != null && plugin.pack().has(p);
        Component subtitle = money > 0 ? Text.c("&e+0 " + plugin.eco().currency()) : Text.c(sub == null ? "" : sub);
        List<Integer> sizes = List.of(4, 8, 14, 12);
        String probe = f.title == null ? "" : titleGlyphs().getString(f.title + "_title_12.text", "");
        if (!pack || probe.isEmpty()) {
            p.showTitle(Title.title(Text.c(fallbackTitle), subtitle, Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(4200), Duration.ofMillis(900))));
        } else {
            for (int i = 0; i < sizes.size(); i++) {
                String chars = titleGlyphs().getString(f.title + "_title_" + sizes.get(i) + ".text", probe);
                boolean last = i == sizes.size() - 1;
                Component title = Component.text(chars).font(net.kyori.adventure.key.Key.key("minesmp", "title"));
                Title.Times times = Title.Times.times(Duration.ZERO, Duration.ofMillis(last ? 4200 : 400), Duration.ofMillis(last ? 900 : 0));
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) p.showTitle(Title.title(title, subtitle, times)); }, i * 2L);
            }
        }
        if (money <= 0) return;
        // Betrag zaehlt hoch: schnell los, zum Ende langsamer
        String cur = plugin.eco().currency();
        int steps = 16;
        for (int i = 1; i <= steps; i++) {
            final double t = (double) i / steps, v = money * (1 - Math.pow(1 - t, 3));
            final boolean done = i == steps;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                p.sendTitlePart(TitlePart.SUBTITLE, Text.c((done ? "&e&l+" : "&e+") + Text.money(done ? money : Math.floor(v)) + " " + cur));
                if (cfg.getBoolean("effects.sounds", true)) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, (float) (0.8 + t));
            }, 8L + i * 2L);
        }
    }

    // ================================================================ Takte

    private boolean goldenHourNow() {
        if (!cfg.getBoolean("golden-hour.enabled", true)) return false;
        return goldenMinutes() < Math.max(1, cfg.getInt("golden-hour.minutes", 60));
    }

    /** Minuten seit Beginn der heutigen Goldenen Stunde (0..1439). */
    private int goldenMinutes() {
        int startMin = minutes(cfg.getString("golden-hour.start", "19:00"), 19 * 60);
        LocalTime t = LocalTime.now(RewardService.ZONE);
        return Math.floorMod(t.getHour() * 60 + t.getMinute() - startMin, 1440);
    }

    /** Text fuers Menue: "laeuft noch 23 Min." oder "in 3 Std. 12 Min.". */
    public String goldenHourText() {
        if (!cfg.getBoolean("golden-hour.enabled", true)) return "aus";
        int len = Math.max(1, cfg.getInt("golden-hour.minutes", 60)), since = goldenMinutes();
        if (since < len) return "laeuft noch " + (len - since) + " Min.";
        int in = 1440 - since;
        return "in " + (in >= 60 ? (in / 60) + " Std. " : "") + (in % 60) + " Min.";
    }

    private void second() {
        if (ended) return;
        long now = System.currentTimeMillis();
        if (now >= end) { finish(true, "Zeit abgelaufen"); return; }
        boolean gh = goldenHourNow();
        if (gh != goldenHour) {
            goldenHour = gh;
            if (gh) {
                Msg.broadcast("goldrush.golden-start", "{prefix}&e&lGOLDENE STUNDE! &7Im Goldrausch hast du jetzt &ex{mult} Glueck &7- {minutes} Minuten lang.",
                        "mult", trim(cfg.getDouble("golden-hour.multiplier", 2.0)), "minutes", String.valueOf(cfg.getInt("golden-hour.minutes", 60)));
                for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), Sound.BLOCK_BELL_USE, 0.9f, 1.2f);
            } else {
                Msg.broadcast("goldrush.golden-end", "{prefix}&7Die &eGoldene Stunde &7ist vorbei. Morgen wieder!");
            }
        }
        long left = end - now;
        for (long mark : new long[]{86_400_000L, 3_600_000L, 600_000L, 60_000L}) {
            if (lastLeft > mark && left <= mark)
                Msg.broadcast("goldrush.ending", "{prefix}&6Der Goldrausch endet in &f{left}&6! &7Letzte Chance auf Funde und die Rangliste: &b/goldrausch", "left", TimeUtil.format(mark));
        }
        lastLeft = left;
    }

    public static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.GERMANY, "%.1f", v);
    }

    private void barTick() {
        if (sessions.isEmpty()) return;
        long now = System.currentTimeMillis(), window = feverWindow();
        for (var en : sessions.entrySet()) {
            Session s = en.getValue();
            if (s.bar == null) continue;
            long rest = window - (now - s.last);
            Player p = Bukkit.getPlayer(en.getKey());
            if (rest <= 0 || p == null || ended) {
                if (p != null) p.hideBossBar(s.bar);
                s.bar = null;
                s.streak = 0;
                continue;
            }
            s.bar.progress(Math.max(0f, Math.min(1f, (float) rest / window)));
        }
    }

    /** Alle 15 Sekunden: Rangliste und Stand sichern. */
    private void flushTick() {
        if (!dirty.isEmpty()) {
            writeScores();
            saveState();
        }
        if (active()) refreshTop();
    }

    private void writeScore(Player p) {
        PlayerData d = plugin.data().getIfLoaded(p.getUniqueId());
        if (d == null || d.stat("gr_season") != season) return;
        plugin.db().update("REPLACE INTO goldrush (season, uuid, name, money, ores) VALUES (?, ?, ?, ?, ?)",
                season, p.getUniqueId().toString(), p.getName(), d.stat("gr_cents") / 100.0, d.stat("gr_ores"));
    }

    private void writeScores() {
        for (UUID id : dirty) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) writeScore(p);
        }
        dirty.clear();
    }

    private static List<Top> queryTop(java.sql.Connection c, long season, int limit) throws java.sql.SQLException {
        List<Top> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, money, ores FROM goldrush WHERE season = ? AND money > 0 ORDER BY money DESC, ores DESC LIMIT " + limit)) {
            ps.setLong(1, season);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Top(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getLong(4)));
            }
        }
        return out;
    }

    private void refreshTop() {
        final long s = season;
        plugin.db().queryThen(c -> queryTop(c, s, 100), list -> { if (s == season) top = list; });
    }

    /** Rangliste (bis 100), der Betrachter mit seinem aktuellen Stand. */
    public List<Top> top(Player viewer) {
        List<Top> list = new ArrayList<>(top);
        if (viewer != null) {
            String id = viewer.getUniqueId().toString();
            double mine = won(viewer);
            list.removeIf(t -> t.uuid().equals(id));
            if (mine > 0) list.add(new Top(id, viewer.getName(), mine, ores(viewer)));
            list.sort((a, b) -> Double.compare(b.money(), a.money()));
        }
        return list;
    }

    /** Platz des Spielers (0 = noch nichts gewonnen). */
    public int rank(Player p) {
        double mine = won(p);
        if (mine <= 0) return 0;
        String id = p.getUniqueId().toString();
        int rank = 1;
        for (Top t : top) if (!t.uuid().equals(id) && t.money() > mine) rank++;
        return rank;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.remove(p.getUniqueId());
        helmEffect.remove(p.getUniqueId());
        stopConfirm.remove(p.getUniqueId());
        if (s != null && s.bar != null) p.hideBossBar(s.bar);
        if (dirty.remove(p.getUniqueId())) writeScore(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = e.getPlayer();
            if (!p.isOnline()) return;
            if (active())
                Msg.send(p, "goldrush.join", "{prefix}&6&lGOLDRAUSCH &7laeuft noch &f{left}&7: in jedem Erz kann ein Fund stecken - bis zum &6Goldticket&7. Alles dazu: &b/goldrausch", "left", TimeUtil.format(left() - left() % 60_000));
            if (hasTicket(p))
                Msg.send(p, "goldrush.ticket-join", "{prefix}&6Dein Goldticket gilt noch &f{left}&6. {daily}", "left", shortSpan(ticketUntil(p) - System.currentTimeMillis()),
                        "daily", dailyReady(p) ? "&7Heutige Gratis-Schluessel abholen: &b/goldrausch ticket" : "&7Die heutigen Schluessel hast du schon.");
        }, 80L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFireworkDamage(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Firework f && f.getPersistentDataContainer().has(fwKey)) e.setCancelled(true);
    }

    /** Beim Herunterfahren: Rangliste und Stand sichern. */
    public void shutdown() {
        for (var en : sessions.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null && en.getValue().bar != null) p.hideBossBar(en.getValue().bar);
        }
        writeScores();
        if (season > 0) saveState();
    }

    // ================================================================ Start und Ende

    public boolean start(CommandSender by, long durationMs) {
        if (active()) return false;
        long now = System.currentTimeMillis();
        season++;
        start = now;
        end = now + durationMs;
        ended = false;
        totalOres = 0;
        communityBonus = bonusFor(0);
        starter = by.getName();
        salt = ThreadLocalRandom.current().nextLong();
        ticketsTotal = 0;
        ticketsToday = 0;
        ticketDay = today();
        finders = new ArrayList<>();
        top = List.of();
        lastLeft = Long.MAX_VALUE;
        dirty.clear();
        sessions.values().forEach(s -> s.streak = 0);
        goldenHour = goldenHourNow();
        saveState();
        plugin.audit().log(by, "GOLDRAUSCH_START", "Runde " + season + ", " + TimeUtil.format(durationMs));
        String time = TimeUtil.format(durationMs);
        Msg.broadcast("goldrush.start", "&8&m                                                  \n &6&l⛏ GOLDRAUSCH &7- gestartet von &f{by}\n &7{time} lang kann in &fjedem Erz &7ein Fund stecken:\n &6Goldnugget&7, &6Goldbarren&7, &eStar-Tool&7, &dSpawner &7- und das &6&lGoldticket&7.\n &7Alle Infos: &b/goldrausch\n&8&m                                                  ",
                "by", by.getName(), "time", time);
        Title title = Title.title(Text.c("<g:#FFF3B0:#FFC83D:#D98E04>&lGOLDRAUSCH</g>"), Text.c("&fAn die Spitzhacken! &8| &b/goldrausch"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofSeconds(1)));
        for (Player o : Bukkit.getOnlinePlayers()) {
            o.showTitle(title);
            o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            o.playSound(o.getLocation(), Sound.EVENT_RAID_HORN, 30f, 1.1f);
        }
        announce("GOLDRAUSCH", "&7gestartet - &f" + time + " &7lang: jedes Erz kann es sein &8| &b/goldrausch", 20);
        plugin.webhook().send(cfg.getString("webhook-channel", "events"), "Goldrausch gestartet",
                "Gestartet von " + by.getName() + ". " + time + " lang kann in jedem Erz ein Fund stecken - bis zum Goldticket. Infos im Spiel: /goldrausch", 0xFFC83D);
        return true;
    }

    /** Leiste oben in der Mitte fuer alle, laeuft in seconds Sekunden ab. */
    private void announce(String word, String rest, int seconds) {
        BossBar packBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
        BossBar textBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
        List<Player> viewers = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player o : viewers) o.showBossBar(plugin.pack() != null && plugin.pack().has(o) ? packBar : textBar);
        int total = seconds * 20;
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t >= total) {
                    for (Player o : viewers) { o.hideBossBar(packBar); o.hideBossBar(textBar); }
                    cancel();
                    return;
                }
                String shine = Text.gradient(word, (t % 40) / 40.0, "&l", "#FFB000", "#FFF6C2", "#FFB000");
                packBar.name(Text.c(plugin.pack() == null ? shine + " " + rest : plugin.pack().glyphs("&f:ticket: " + shine + " " + rest + " &f:ticket:")));
                textBar.name(Text.c("&6✦ " + shine + " " + rest + " &6✦"));
                float prog = Math.max(0f, 1f - (float) t / total);
                packBar.progress(prog);
                textBar.progress(prog);
                t += 4;
            }
        }.runTaskTimer(plugin, 0L, 4L);
    }

    /** Beendet den Goldrausch. payout = Rangliste auszahlen (sonst Abbruch ohne Belohnungen). */
    public void finish(boolean payout, String reason) {
        if (ended) return;
        ended = true;
        end = Math.min(end, System.currentTimeMillis());
        for (UUID id : sessions.keySet()) dirty.add(id);
        writeScores();
        for (var en : sessions.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null && en.getValue().bar != null) p.hideBossBar(en.getValue().bar);
            en.getValue().bar = null;
            en.getValue().streak = 0;
        }
        goldenHour = false;
        saveState();
        plugin.audit().log("Server", "GOLDRAUSCH_ENDE", "Runde " + season + ": " + reason + (payout ? "" : " (ohne Auszahlung)"));
        final long s = season;
        plugin.db().queryThen(c -> queryTop(c, s, 100), list -> {
            top = list;
            winners = new ArrayList<>();
            for (int i = 0; i < Math.min(10, list.size()); i++) winners.add(list.get(i).name() + ":" + list.get(i).money());
            saveState();
            if (!payout) {
                Msg.broadcast("goldrush.cancelled", "{prefix}&7Der Goldrausch wurde vom Team &cabgebrochen&7. Es gibt keine Ranglisten-Belohnungen.");
                return;
            }
            Msg.broadcast("goldrush.end-head", "&8&m                                                  \n &6&l⛏ GOLDRAUSCH BEENDET &7- die Rangliste:");
            String[] medal = {"&e&l1.", "&f&l2.", "&6&l3."};
            for (int i = 0; i < Math.min(3, list.size()); i++)
                Msg.broadcast("goldrush.end-line", " {place} &f{name} &8- &a{money} &8({ores} Erze)", "place", medal[i], "name", list.get(i).name(),
                        "money", plugin.eco().format(list.get(i).money()), "ores", Text.money(list.get(i).ores()));
            if (list.isEmpty()) Msg.broadcast("goldrush.end-nobody", " &7Niemand hat etwas gefunden.");
            Msg.broadcast("goldrush.end-foot", " &7Die Belohnungen wurden verteilt. Goldstaub kannst du weiter im Shop ausgeben: &b/goldrausch shop\n&8&m                                                  ");
            for (int i = 0; i < list.size(); i++) reward(list.get(i), i + 1);
            Title title = Title.title(Text.c("<g:#FFF3B0:#FFC83D:#D98E04>&lGOLDRAUSCH BEENDET</g>"),
                    Text.c(list.isEmpty() ? "&7Danke fuers Mitmachen!" : "&7Sieger: &f&l" + list.get(0).name() + " &8| &b/goldrausch top"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofSeconds(1)));
            for (Player o : Bukkit.getOnlinePlayers()) {
                o.showTitle(title);
                o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.9f);
            }
            if (!list.isEmpty()) plugin.webhook().send(cfg.getString("webhook-channel", "events"), "Goldrausch beendet",
                    "Sieger: " + list.get(0).name() + " mit " + plugin.eco().format(list.get(0).money()), 0xFFC83D);
        });
    }

    /** Belohnung fuer einen Platz der Rangliste - auch wenn der Spieler offline ist (Abholfach, Gutschrift). */
    private void reward(Top t, int rank) {
        ConfigurationSection r = rewardFor(rank);
        if (r == null) return;
        UUID id;
        try { id = UUID.fromString(t.uuid()); } catch (IllegalArgumentException e) { return; }
        Player p = Bukkit.getPlayer(id);
        List<String> parts = new ArrayList<>();
        double money = r.getDouble("money");
        if (money > 0) { plugin.eco().depositAny(id, money, "GOLDRAUSCH_RANG", "Platz " + rank); parts.add("&a" + plugin.eco().format(money)); }
        long gems = r.getLong("gems");
        if (gems > 0 && plugin.tokens() != null) { plugin.tokens().give(id, de.minesmp.core.economy.TokenService.Kind.GEMS, gems, "Goldrausch Platz " + rank); parts.add("&d" + gems + " Gems"); }
        ConfigurationSection keys = r.getConfigurationSection("keys");
        if (keys != null && plugin.crates() != null) for (String k : keys.getKeys(false)) {
            plugin.crates().giveKeys(id, k, keys.getInt(k));
            parts.add("&e" + keys.getInt(k) + "x " + plugin.crates().displayName(k) + "-Schluessel");
        }
        String date = LocalDate.now(RewardService.ZONE).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        for (String ci : r.getStringList("custom-items")) {
            ItemStack it = plugin.customItems().create(ci);
            if (it == null) continue;
            it.editMeta(m -> {
                List<Component> lore = m.hasLore() ? new ArrayList<>(m.lore()) : new ArrayList<>();
                lore.add(Text.item("&8" + t.name() + " - Platz " + rank + " - " + date));
                m.lore(lore);
            });
            parts.add("&f" + Text.strip(Items.name(it)));
            if (p != null) Items.giveOrDrop(p, it);
            else plugin.auction().mail(id, it, "Goldrausch");
        }
        plugin.audit().log(t.name(), "GOLDRAUSCH_RANG", "Platz " + rank + ": " + Text.strip(String.join(", ", parts)));
        if (p != null) Msg.send(p, "goldrush.rank-reward", "{prefix}&6Goldrausch - Platz &f{rank}&6! &7Deine Belohnung: {list}", "rank", String.valueOf(rank), "list", String.join("&7, ", parts));
    }

    public ConfigurationSection rewardFor(int rank) {
        ConfigurationSection rs = cfg.getConfigurationSection("rewards");
        if (rs == null) return null;
        for (String k : rs.getKeys(false)) {
            try {
                String[] ab = k.split("-");
                int a = Integer.parseInt(ab[0].trim()), b = ab.length > 1 ? Integer.parseInt(ab[1].trim()) : a;
                if (rank >= a && rank <= b) return rs.getConfigurationSection(k);
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    /** Lesbare Liste einer Belohnung (fuer Menue-Texte). */
    public List<String> rewardLines(ConfigurationSection r) {
        List<String> out = new ArrayList<>();
        if (r == null) return out;
        if (r.getDouble("money") > 0) out.add("&a" + plugin.eco().format(r.getDouble("money")));
        if (r.getLong("dust") > 0) out.add("&6" + r.getLong("dust") + " Goldstaub");
        if (r.getLong("gems") > 0) out.add("&d" + r.getLong("gems") + " Gems");
        ConfigurationSection keys = r.getConfigurationSection("keys");
        if (keys != null && plugin.crates() != null) for (String k : keys.getKeys(false)) out.add("&e" + keys.getInt(k) + "x " + plugin.crates().displayName(k) + "-Schluessel");
        for (String ci : r.getStringList("custom-items")) {
            ItemStack it = plugin.customItems().create(ci);
            if (it != null) out.add("&f" + Text.strip(Items.name(it)));
        }
        return out;
    }

    /** Meilenstein abholen. */
    public boolean claim(Player p, int index) {
        if (index < 0 || index >= milestones.size() || index > 62) return false;
        Milestone m = milestones.get(index);
        PlayerData d = data(p);
        if ((d.stat("gr_claimed") & (1L << index)) != 0 || d.stat("gr_ores") < m.ores()) return false;
        d.stats.put("gr_claimed", d.stat("gr_claimed") | (1L << index));
        d.dirty = true;
        long dust = m.reward().getLong("dust");
        if (dust > 0) addDust(p, dust);
        String got = plugin.rewards().give(p, m.reward(), "Goldrausch " + m.name());
        Msg.send(p, "goldrush.milestone-claimed", "{prefix}&6Meilenstein &f{name} &6abgeholt: {list}", "name", m.name(),
                "list", (dust > 0 ? "&6" + dust + " Goldstaub&7, " : "") + got);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
        plugin.audit().log(p.getName(), "GOLDRAUSCH_ZIEL", m.name());
        return true;
    }

    /** Kauf im Goldstaub-Shop. */
    public boolean buy(Player p, ShopEntry e) {
        ItemStack it = plugin.customItems().create(e.item());
        if (it == null) { Msg.send(p, "goldrush.shop-missing", "{prefix}&cDieses Item gibt es gerade nicht."); return false; }
        PlayerData d = data(p);
        if (d.stat("gr_dust") < e.price()) {
            Msg.send(p, "goldrush.shop-poor", "{prefix}&cDir fehlen &f{n} Goldstaub&c. Goldstaub bekommst du fuer jedes Erz im Goldrausch.", "n", Text.money(e.price() - d.stat("gr_dust")));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return false;
        }
        d.stats.put("gr_dust", d.stat("gr_dust") - e.price());
        d.dirty = true;
        for (int i = 0; i < e.amount(); i++) Items.giveOrDrop(p, plugin.customItems().create(e.item()));
        plugin.eco().log(p.getUniqueId(), null, e.price(), "GOLDSTAUB", e.item());
        Msg.send(p, "goldrush.shop-bought", "{prefix}&aGekauft: &f{amount}x {item} &7fuer &6{price} Goldstaub&7.", "amount", String.valueOf(e.amount()),
                "item", Text.strip(Items.name(it)), "price", Text.money(e.price()));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        return true;
    }

    // ================================================================ Items

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        ItemStack hand = e.getItem();
        String ab = ability(hand);
        if (!ab.startsWith("GOLD_")) return;
        Player p = e.getPlayer();
        boolean onUsable = e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable() && !p.isSneaking();
        switch (ab) {
            case "GOLD_PFANNE" -> { if (!onUsable) { e.setCancelled(true); pan(p); } }
            case "GOLD_RUTE" -> { if (!onUsable) { e.setCancelled(true); rod(p, hand); } }
            case "GOLD_GUTSCHEIN" -> { if (!onUsable) { e.setCancelled(true); voucher(p, hand); } }
            default -> { }
        }
    }

    /** Premium-Gutschein einloesen: gibt dem Spieler, der ihn benutzt, den Rang. */
    private void voucher(Player p, ItemStack hand) {
        if (!plugin.cooldowns().tryUse(p.getUniqueId(), "gr_voucher", 1000)) return;
        String rank = cfg.getString("ticket.premium-rank", "premium");
        int days = Math.max(1, cfg.getInt("ticket.premium-days", 30));
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) { Msg.send(p, "goldrush.voucher-off", "{prefix}&cDer Gutschein kann gerade nicht eingeloest werden (LuckPerms fehlt)."); return; }
        if (p.isPermissionSet("group." + rank) && p.hasPermission("group." + rank)) {
            Msg.send(p, "goldrush.voucher-has", "{prefix}&7Du hast den Rang schon. Gib den Gutschein einem Freund - er loest ihn mit Rechtsklick ein.");
            return;
        }
        hand.setAmount(hand.getAmount() - 1);
        giveRank(p, rank, days);
        Msg.send(p, "goldrush.voucher-used", "{prefix}&bGutschein eingeloest: &f{days} Tage {rank}&b. Viel Spass!", "days", String.valueOf(days), "rank", Text.capitalize(rank));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
        plugin.audit().log(p.getName(), "GOLDRAUSCH_GUTSCHEIN", rank + " " + days + "d");
        if (plugin.scoreboard() != null) Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.scoreboard().refreshNametags(), 20L);
    }

    private static boolean water(Block b) {
        if (b == null) return false;
        Material m = b.getType();
        return m == Material.WATER || m == Material.WATER_CAULDRON || (b.getBlockData() instanceof Waterlogged w && w.isWaterlogged());
    }

    /** Goldpfanne: ein Kies aus dem Inventar im Wasser waschen. */
    private void pan(Player p) {
        Session s = session(p);
        long now = System.currentTimeMillis();
        if (s.panUntil > now) return;
        if (!active()) { Msg.actionbar(p, "goldrush.pan-off", "&7Die Goldpfanne funktioniert nur waehrend des &6Goldrauschs&7."); return; }
        RayTraceResult hit = p.rayTraceBlocks(5, FluidCollisionMode.ALWAYS);
        Block target = hit == null ? null : hit.getHitBlock();
        if (!water(target)) target = water(p.getLocation().getBlock()) ? p.getLocation().getBlock() : null;
        if (target == null) { Msg.actionbar(p, "goldrush.pan-water", "&7Schau auf &bWasser &7(oder stell dich hinein)."); return; }
        if (!p.getInventory().containsAtLeast(new ItemStack(Material.GRAVEL), 1)) { Msg.actionbar(p, "goldrush.pan-gravel", "&7Du brauchst &fKies &7im Inventar."); return; }
        p.getInventory().removeItem(new ItemStack(Material.GRAVEL, 1));
        int ticks = (int) Math.max(10, cfg.getDouble("items.pan-seconds", 2.5) * 20);
        s.panUntil = now + ticks * 50L;
        Location at = target.getLocation().add(0.5, 1.0, 0.5);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!p.isOnline() || !p.getWorld().equals(at.getWorld()) || p.getLocation().distanceSquared(at) > 64) { cancel(); return; }
                if (t >= ticks) { panResult(p, at); cancel(); return; }
                at.getWorld().spawnParticle(Particle.SPLASH, at, 8, 0.25, 0.05, 0.25, 0.02);
                if (t % 10 == 0) p.playSound(at, Sound.ENTITY_GENERIC_SWIM, 0.35f, 1.6f);
                Msg.markActionbar(p, 800);
                p.sendActionBar(Text.c("&b" + "≈".repeat(1 + (t / 5) % 4) + " &7Gold waschen ..."));
                t += 5;
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    private void panResult(Player p, Location at) {
        if (!active()) return;
        ConfigurationSection c = cfg.getConfigurationSection("items.pan");
        double r = ThreadLocalRandom.current().nextDouble(), acc = 0;
        if (c != null) {
            acc += c.getDouble("nugget.chance", 0.004);
            if (r < acc) { grant(p, at, Find.NUGGET, "Goldpfanne"); return; }
            for (String kind : List.of("dust-big", "dust-small")) {
                acc += c.getDouble(kind + ".chance", 0);
                if (r >= acc) continue;
                int n = roll(range(c, kind + ".amount", 1, 2));
                addDust(p, n);
                dirty.add(p.getUniqueId());
                Msg.actionbar(p, "goldrush.pan-dust", "&6+{n} Goldstaub", "n", String.valueOf(n));
                at.getWorld().spawnParticle(Particle.WAX_ON, at, 8, 0.25, 0.15, 0.25, 0.03);
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
                return;
            }
        }
        Msg.actionbar(p, "goldrush.pan-nothing", "&7Nur Kies ... weiter waschen.");
    }

    /** Wuenschelrute: naechstes natuerliches Erz anzeigen (leuchtet nur fuer diesen Spieler). */
    private void rod(Player p, ItemStack hand) {
        long cd = plugin.cooldowns().remaining(p.getUniqueId(), "gr_rod");
        if (cd > 0) { Msg.actionbar(p, "goldrush.rod-cooldown", "&7Die Wuenschelrute ruht noch &f{s}s&7.", "s", String.valueOf(cd / 1000 + 1)); return; }
        int r = Math.max(3, Math.min(16, cfg.getInt("items.rod-radius", 10)));
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        int cx = eye.getBlockX(), cy = eye.getBlockY(), cz = eye.getBlockZ();
        Block best = null;
        int bestD = Integer.MAX_VALUE;
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            if (!w.isChunkLoaded((cx + x) >> 4, (cz + z) >> 4)) continue;
            for (int y = -r; y <= r; y++) {
                int dd = x * x + y * y + z * z;
                if (dd > r * r || dd >= bestD || cy + y < w.getMinHeight() || cy + y >= w.getMaxHeight()) continue;
                Block b = w.getBlockAt(cx + x, cy + y, cz + z);
                if (!dustPer.containsKey(b.getType()) || (plugin.skills() != null && plugin.skills().isPlaced(b))) continue;
                best = b;
                bestD = dd;
            }
        }
        plugin.cooldowns().set(p.getUniqueId(), "gr_rod", Math.max(1, cfg.getInt("items.rod-cooldown-seconds", 20)) * 1000L);
        plugin.customItems().useCharge(p, hand);
        if (best == null) {
            Msg.actionbar(p, "goldrush.rod-nothing", "&7Die Rute bleibt ruhig - kein Erz in &f{r} Bloecken&7.", "r", String.valueOf(r));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            return;
        }
        final Block ore = best;
        if (plugin.guard() != null) plugin.guard().knownOre(p, ore);
        BlockDisplay bd = w.spawn(ore.getLocation(), BlockDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.setBlock(ore.getBlockData());
            d.setGlowing(true);
            d.setGlowColorOverride(Color.fromRGB(0xFFC83D));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(-0.01f, -0.01f, -0.01f), new Quaternionf(), new Vector3f(1.02f, 1.02f, 1.02f), new Quaternionf()));
        });
        p.showEntity(plugin, bd);
        Bukkit.getScheduler().runTaskLater(plugin, bd::remove, 140L);
        Location to = ore.getLocation().add(0.5, 0.5, 0.5);
        org.bukkit.util.Vector dir = to.toVector().subtract(eye.toVector());
        double len = dir.length();
        if (len > 0.5) {
            dir.normalize();
            for (double t = 0.8; t < len; t += 0.5) p.spawnParticle(Particle.WAX_ON, eye.clone().add(dir.clone().multiply(t)), 1, 0, 0, 0, 0);
        }
        int dy = ore.getY() - cy;
        Msg.actionbar(p, "goldrush.rod-found", "&6{ore} &7in &f{d} Bloecken &8({dir})", "ore", oreName(baseOf.getOrDefault(ore.getType(), ore.getType())),
                "d", String.valueOf((int) Math.round(Math.sqrt(bestD))), "dir", dy > 1 ? "ueber dir" : dy < -1 ? "unter dir" : "auf deiner Hoehe");
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.5f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent e) {
        if (!ability(e.getItem()).equals("GOLD_ELIXIER")) return;
        Player p = e.getPlayer();
        if (!active()) {
            e.setCancelled(true);
            Msg.actionbar(p, "goldrush.elixir-off", "&7Das Elixier wirkt nur waehrend des &6Goldrauschs&7.");
            return;
        }
        PlayerData d = plugin.data().get(p);
        long now = System.currentTimeMillis();
        long until = Math.max(now, d.stat("gr_elixir_until")) + Math.max(1, cfg.getInt("items.elixir-minutes", 15)) * 60_000L;
        d.stats.put("gr_elixir_until", until);
        d.dirty = true;
        Msg.send(p, "goldrush.elixir", "{prefix}&dGoldfieber-Elixier: &7jetzt &ax{mult} Glueck &7im Goldrausch - noch &f{left}&7.",
                "mult", trim(cfg.getDouble("items.elixir-multiplier", 1.5)), "left", TimeUtil.format(until - now));
        p.playSound(p.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 1f, 1.3f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onThrow(PlayerLaunchProjectileEvent e) {
        if (!ability(e.getItemStack()).equals("GOLD_DYNAMIT")) return;
        Player p = e.getPlayer();
        Session s = session(p);
        long now = System.currentTimeMillis();
        if (now - s.lastThrow < cfg.getLong("items.dynamite-cooldown-ms", 800)) { e.setCancelled(true); return; }
        s.lastThrow = now;
        e.getProjectile().getPersistentDataContainer().set(dynKey, PersistentDataType.STRING, p.getUniqueId().toString());
        p.playSound(p.getLocation(), Sound.ENTITY_TNT_PRIMED, 0.6f, 1.5f);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(ProjectileHitEvent e) {
        String owner = e.getEntity().getPersistentDataContainer().get(dynKey, PersistentDataType.STRING);
        if (owner == null) return;
        if (e.getHitEntity() != null) { e.setCancelled(true); return; }      // fliegt durch Spieler und Tiere, kein Treffer
        if (e.getHitBlock() == null) return;
        Location at = e.getEntity().getLocation();
        UUID id;
        try { id = UUID.fromString(owner); } catch (IllegalArgumentException ex) { return; }
        ItemStack look = plugin.customItems().create("dynamit");
        World w = at.getWorld();
        ItemDisplay disp = look == null ? null : w.spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(look);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(0, 0.2f, 0), new Quaternionf(), new Vector3f(0.6f, 0.6f, 0.6f), new Quaternionf()));
        });
        int fuse = Math.max(5, cfg.getInt("items.dynamite-fuse-ticks", 30));
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t >= fuse) {
                    if (disp != null) disp.remove();
                    explode(Bukkit.getPlayer(id), at);
                    cancel();
                    return;
                }
                w.spawnParticle(Particle.SMOKE, at.clone().add(0, 0.45, 0), 2, 0.03, 0.05, 0.03, 0.01);
                if (t % 10 == 0) w.playSound(at, Sound.ENTITY_CREEPER_PRIMED, 0.5f, 1.6f);
                t += 5;
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    private static boolean blastable(Block b) {
        Material m = b.getType();
        if (m.isAir() || b.isLiquid() || m.getHardness() < 0 || m.getHardness() >= 40 || m == Material.SPAWNER) return false;
        if (!Tag.MINEABLE_PICKAXE.isTagged(m) && !Tag.MINEABLE_SHOVEL.isTagged(m)) return false;
        return !(b.getState(false) instanceof TileState);
    }

    /** Sprengt Stein, Erde und Erze frei. Jeder Block laeuft als normaler Abbau des Werfers (Schutz, Protokoll, Goldrausch). */
    private void explode(Player p, Location at) {
        World w = at.getWorld();
        w.spawnParticle(Particle.EXPLOSION, at, 2, 0.4, 0.4, 0.4, 0);
        w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at, 10, 0.5, 0.5, 0.5, 0.02);
        w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.25f);
        if (p == null || !p.isOnline() || !p.getWorld().equals(w) || p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.ADVENTURE) return;
        int r = Math.max(1, Math.min(4, cfg.getInt("items.dynamite-radius", 2)));
        Block c = at.getBlock();
        List<Block> blocks = new ArrayList<>();
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            if (x * x + y * y + z * z > r * r + 1) continue;
            Block b = c.getRelative(x, y, z);
            if (b.getY() >= w.getMinHeight() && b.getY() < w.getMaxHeight() && blastable(b)) blocks.add(b);
        }
        blocks.sort(java.util.Comparator.comparingDouble(b -> b.getLocation().distanceSquared(c.getLocation())));
        ItemStack tool = new ItemStack(Material.NETHERITE_PICKAXE);
        UUID id = p.getUniqueId();
        plugin.customItems().quiet(id, true);
        if (plugin.dragonGear() != null) plugin.dragonGear().quiet(id, true);
        try {
            for (Block b : blocks) {
                if (!blastable(b)) continue;
                BlockBreakEvent ev = new BlockBreakEvent(b, p);
                Bukkit.getPluginManager().callEvent(ev);
                if (ev.isCancelled()) continue;
                b.breakNaturally(tool, false, true);
            }
        } finally {
            plugin.customItems().quiet(id, false);
            if (plugin.dragonGear() != null) plugin.dragonGear().quiet(id, false);
        }
    }

    /** Grubenhelm: Nachtsicht und Eile, solange er getragen wird. */
    private void helmTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean worn = ability(p.getInventory().getHelmet()).equals("GOLD_HELM");
            UUID id = p.getUniqueId();
            if (worn) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 320, 0, true, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 100, 0, true, false, false));
                helmEffect.add(id);
            } else if (helmEffect.remove(id)) {
                PotionEffect nv = p.getPotionEffect(PotionEffectType.NIGHT_VISION);
                if (nv != null && nv.getDuration() <= 320 && nv.isAmbient()) p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            }
        }
    }

    private boolean goldItem(ItemStack it) { return ability(it).startsWith("GOLD_"); }

    /** Goldrausch-Items sind keine Zutaten: nicht verbasteln, nicht verheizen, nicht an Piglins oder Pferde geben. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack s : e.getInventory().getMatrix()) if (s != null && goldItem(s)) { e.getInventory().setResult(null); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFuel(FurnaceBurnEvent e) { if (goldItem(e.getFuel())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onEntityUse(PlayerInteractEntityEvent e) {
        ItemStack it = e.getHand() == EquipmentSlot.OFF_HAND ? e.getPlayer().getInventory().getItemInOffHand() : e.getPlayer().getInventory().getItemInMainHand();
        String ab = ability(it);
        if (ab.equals("GOLD_DEKO") || ab.equals("GOLD_HUFEISEN") || ab.equals("GOLD_GUTSCHEIN")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiglin(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Piglin && goldItem(e.getItem().getItemStack())) e.setCancelled(true);
    }

    // ================================================================ Befehle

    private void command(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        if (!(s instanceof Player p) || sub.equals("info") || sub.equals("hilfe")) { info(s); return; }
        GoldrushMenu.Tab tab = switch (sub) {
            case "funde", "fund" -> GoldrushMenu.Tab.FUNDE;
            case "erze", "glueck" -> GoldrushMenu.Tab.ERZE;
            case "ticket", "goldticket" -> GoldrushMenu.Tab.TICKET;
            case "shop" -> GoldrushMenu.Tab.SHOP;
            case "ziele", "meilensteine" -> GoldrushMenu.Tab.ZIELE;
            case "top", "rangliste" -> GoldrushMenu.Tab.TOP;
            case "start", "mein", "dein", "stats" -> GoldrushMenu.Tab.START;
            default -> GoldrushMenu.Tab.TICKET;
        };
        new GoldrushMenu(this, p, tab).open(p);
    }

    private void info(CommandSender s) {
        String state = active() ? "&alaeuft noch &f" + TimeUtil.format(left() - left() % 60_000) : season > 0 ? "&7beendet" : "&7noch nicht gestartet";
        Msg.send(s, "goldrush.info", "{prefix}&6&lGoldrausch &8- {state}\n&8» &7In &fjedem natuerlichen Erz &7kann ein Fund stecken (gesetzte Erze zaehlen nicht).\n&8» &6Goldnugget &7und &6Goldbarren&7: Geld direkt aufs Konto. &eStar-Tool &7und &dSpawner&7: als Item.\n&8» &6&lGoldticket &7(Hauptgewinn): {days} Tage taeglich Gratis-Schluessel, Premium, goldener Name.\n&8» &aGlueck&7: zuegig weiter abbauen (Goldfieber), &eGoldene Stunde &7um &f{gh} Uhr&7, Server-Ziel, Items.\n&8» &7Jedes Erz gibt &6Goldstaub &7fuer den Shop. Am Ende gewinnen die Top 10.\n&8» &7Alles im Menue: &b/goldrausch",
                "state", state, "days", String.valueOf(cfg.getInt("ticket.days", 14)), "gh", cfg.getString("golden-hour.start", "19:00"));
    }

    private void staffCommand(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "start" : a[0].toLowerCase(Locale.ROOT);
        boolean admin = s.hasPermission("minesmp.admin.goldrausch");
        switch (sub) {
            case "start" -> {
                if (active()) {
                    Msg.send(s, "goldrush.already", "{prefix}&eDer Goldrausch laeuft schon - noch &f{left}&e. Vorzeitig beenden: &f/sgoldrausch stop", "left", TimeUtil.format(left()));
                    return;
                }
                long dur = Duration.ofDays(Math.max(1, cfg.getInt("days", 14))).toMillis();
                if (a.length > 1 && admin) {
                    long custom = TimeUtil.parseDuration(a[1]);
                    if (custom > 0) dur = custom;
                }
                start(s, dur);
            }
            case "stop" -> {
                if (!active()) { Msg.send(s, "goldrush.not-running", "{prefix}&7Gerade laeuft kein Goldrausch. Start: &f/sgoldrausch"); return; }
                UUID key = s instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
                Long t = stopConfirm.get(key);
                if (t == null || System.currentTimeMillis() - t > 15_000) {
                    stopConfirm.put(key, System.currentTimeMillis());
                    Msg.send(s, "goldrush.stop-confirm", "{prefix}&eWirklich jetzt beenden? Die Rangliste wird sofort ausgezahlt. Zum Bestaetigen noch einmal &f/sgoldrausch stop &e(15 Sekunden).");
                    return;
                }
                stopConfirm.remove(key);
                finish(true, "beendet von " + s.getName());
            }
            case "abbrechen" -> {
                if (!admin) { Msg.send(s, "no-permission", "{prefix}&cDazu hast du keine Berechtigung."); return; }
                if (!active()) { Msg.send(s, "goldrush.not-running", "{prefix}&7Gerade laeuft kein Goldrausch. Start: &f/sgoldrausch"); return; }
                finish(false, "abgebrochen von " + s.getName());
            }
            case "status" -> Msg.send(s, "goldrush.status", "{prefix}&6Goldrausch &8- &7Runde &f{season}&7: {state}\n&8» &7Gestartet von &f{by} &8| &7Erze gesamt: &f{ores} &8| &7Server-Glueck: &a+{bonus} %\n&8» &7Goldtickets: &f{tickets} &8(heute {today}) &8| &7Goldene Stunde: &f{gh}",
                    "season", String.valueOf(season), "state", active() ? "&alaeuft noch &f" + TimeUtil.format(left()) : "&7nicht aktiv",
                    "by", starter.isEmpty() ? "-" : starter, "ores", Text.money(totalOres), "bonus", pct(communityBonus),
                    "tickets", String.valueOf(ticketsTotal), "today", String.valueOf(ticketsToday()), "gh", goldenHourText());
            case "zeit" -> {
                if (!admin) { Msg.send(s, "no-permission", "{prefix}&cDazu hast du keine Berechtigung."); return; }
                long d = a.length > 1 ? TimeUtil.parseDuration(a[1]) : -1;
                if (!active() || d <= 0) { s.sendMessage(Text.c("&7/sgoldrausch zeit <Dauer> &8- Restzeit setzen, z.B. 2d, 90m, 30s (nur wenn er laeuft)")); return; }
                end = System.currentTimeMillis() + d;
                lastLeft = Long.MAX_VALUE;
                saveState();
                plugin.audit().log(s, "GOLDRAUSCH_ZEIT", TimeUtil.format(d));
                s.sendMessage(Text.c("&aRestzeit gesetzt: " + TimeUtil.format(d)));
            }
            case "staub" -> {
                if (!admin) { Msg.send(s, "no-permission", "{prefix}&cDazu hast du keine Berechtigung."); return; }
                Player t = a.length > 2 ? Bukkit.getPlayerExact(a[1]) : null;
                long n;
                try { n = Long.parseLong(a.length > 2 ? a[2] : ""); } catch (NumberFormatException ex) { n = 0; }
                if (t == null || n == 0) { s.sendMessage(Text.c("&7/sgoldrausch staub <Spieler> <Anzahl>")); return; }
                addDust(t, n);
                plugin.audit().log(s, "GOLDRAUSCH_STAUB", t.getName() + " " + n);
                s.sendMessage(Text.c("&a" + t.getName() + " hat jetzt " + dust(t) + " Goldstaub."));
            }
            case "fund" -> {
                if (!admin) { Msg.send(s, "no-permission", "{prefix}&cDazu hast du keine Berechtigung."); return; }
                Player t = a.length > 2 ? Bukkit.getPlayerExact(a[1]) : null;
                Find f = null;
                if (a.length > 2) for (Find x : Find.values()) if (x.id.equalsIgnoreCase(a[2])) f = x;
                if (t == null || f == null) { s.sendMessage(Text.c("&7/sgoldrausch fund <Spieler> <nugget|bar|startool|spawner|ticket> &8- Fund vergeben (zum Zeigen und Testen)")); return; }
                Location front = t.getEyeLocation().add(t.getLocation().getDirection().multiply(2.2));
                grant(t, front, f, "Team " + s.getName());
                plugin.audit().log(s, "GOLDRAUSCH_FUND_ADMIN", t.getName() + " " + f.id);
                s.sendMessage(Text.c("&a" + f.label + " an " + t.getName() + " vergeben."));
            }
            case "reload" -> {
                if (!admin) { Msg.send(s, "no-permission", "{prefix}&cDazu hast du keine Berechtigung."); return; }
                reload();
                communityBonus = bonusFor(totalOres);
                s.sendMessage(Text.c("&agoldrausch.yml neu geladen."));
            }
            default -> s.sendMessage(Text.c("&7/sgoldrausch &8- starten &7| stop &7| status" + (admin ? " &7| abbrechen | zeit <Dauer> | staub <Spieler> <n> | fund <Spieler> <Art> | reload" : "")));
        }
    }

    /** Einmalig: Rang srmod in LuckPerms anlegen, damit "ab SrMod" ohne Handarbeit stimmt. */
    private void setupLuckPerms() {
        if (!cfg.getBoolean("auto-luckperms", true) || !Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return;
        plugin.db().queryThen(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM kv WHERE k = ?")) {
                ps.setString(1, "lp-srmod");
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        }, done -> {
            if (done) return;
            for (String cmd : List.of("lp creategroup srmod", "lp group srmod setweight 65", "lp group srmod meta setprefix 65 \"&c&lSRMOD &8| &c\"",
                    "lp group srmod parent add moderator", "lp group srmod permission set minesmp.goldrausch.start true"))
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            plugin.db().kvSet("lp-srmod", "1");
            plugin.getLogger().info("LuckPerms: Rang srmod angelegt (Gewicht 65, erbt von moderator, darf /sgoldrausch). Vergeben: lp user <Name> parent add srmod");
        });
    }
}
