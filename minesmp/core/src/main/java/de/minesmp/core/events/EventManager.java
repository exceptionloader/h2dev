package de.minesmp.core.events;

import de.minesmp.core.MineSMP;
import de.minesmp.core.clan.Clan;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import de.minesmp.core.utility.RewardService;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.boss.BarColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Event-System. Typen: KOTH, SUPPLY_DROP, TREASURE_HUNT, COMPETITION (Mining/Fishing/Farming/Woodcutting),
 * BOSS, TOURNAMENT. Dazu Community-Challenge (dauerhaft) und saisonale Events (Datumsbereich + Multiplikator).
 * Startzeiten, Belohnungen und Parameter stehen in events.yml.
 */
public final class EventManager implements Listener {
    private final MineSMP plugin;
    private YamlConfiguration cfg;
    private GameEvent active;
    private String lastMinute = "";
    private final Map<String, Integer> wins = new LinkedHashMap<>();
    private long communityProgress;
    private final NamespacedKey eventKey;

    public EventManager(MineSMP plugin) {
        this.plugin = plugin;
        this.eventKey = new NamespacedKey(plugin, "event");
        reload();
        try {
            String raw = plugin.db().kvGet("event-wins").get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (raw != null) {
                YamlConfiguration y = new YamlConfiguration();
                y.loadFromString(raw);
                for (String k : y.getKeys(false)) wins.put(k, y.getInt(k));
            }
            String cp = plugin.db().kvGet("community-progress").get(5, java.util.concurrent.TimeUnit.SECONDS);
            communityProgress = cp == null ? 0 : Long.parseLong(cp);
        } catch (Exception ignored) {
        }
        plugin.listen(this);
        plugin.command(new SimpleCommand("event", "minesmp.event", "Events", this::playerCmd, (s, a) -> a.length == 1 ? List.of("info", "join", "top", "community") : List.of(), "events"));
        plugin.command(new SimpleCommand("eventadmin", "minesmp.admin.event", "Events verwalten", this::adminCmd, (s, a) -> {
            if (a.length == 1) return List.of("start", "stop", "list", "setkoth", "setboss");
            if (a.length == 2 && a[0].equalsIgnoreCase("start")) return eventIds();
            if (a.length == 2 && a[0].equalsIgnoreCase("setkoth")) return List.of("1", "2");
            return List.of();
        }, "ea"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::second, 20, 20);
    }

    public void reload() {
        cfg = plugin.yaml("events.yml");
    }

    private List<String> eventIds() {
        ConfigurationSection s = cfg.getConfigurationSection("events");
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    // ---------------- Zeitsteuerung ----------------

    private void second() {
        if (active != null) {
            try {
                active.tick();
            } catch (Exception e) {
                plugin.getLogger().warning("Event-Fehler: " + e);
                e.printStackTrace();
                stop(false);
            }
        }
        LocalDateTime now = LocalDateTime.now(RewardService.ZONE);
        String minute = String.format("%02d:%02d", now.getHour(), now.getMinute());
        if (minute.equals(lastMinute)) return;
        lastMinute = minute;
        String day = now.getDayOfWeek().name();
        for (String entry : cfg.getStringList("schedule")) {
            // Format: "MONDAY 20:00 koth" oder "DAILY 18:30 random"
            String[] p = entry.trim().split("\\s+");
            if (p.length < 3) continue;
            if (!(p[0].equalsIgnoreCase("DAILY") || p[0].equalsIgnoreCase(day)) || !p[1].equals(minute)) continue;
            String id = p[2];
            if (id.equalsIgnoreCase("random")) {
                List<String> pool = cfg.getStringList("random-pool");
                if (pool.isEmpty()) continue;
                id = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            }
            if (active == null) start(id, Bukkit.getConsoleSender());
        }
        // Vorwarnung 5 Minuten vorher
        LocalDateTime soon = now.plusMinutes(5);
        String soonStr = String.format("%02d:%02d", soon.getHour(), soon.getMinute());
        for (String entry : cfg.getStringList("schedule")) {
            String[] p = entry.trim().split("\\s+");
            if (p.length >= 3 && (p[0].equalsIgnoreCase("DAILY") || p[0].equalsIgnoreCase(soon.getDayOfWeek().name())) && p[1].equals(soonStr)) {
                Msg.broadcast("event.soon", "{prefix}&eIn 5 Minuten startet ein Event: &f{name}&e!", "name", p[2].equalsIgnoreCase("random") ? "Ueberraschung" : name(p[2]));
            }
        }
    }

    private String name(String id) {
        return cfg.getString("events." + id + ".name", id);
    }

    // ---------------- Befehle ----------------

    private void playerCmd(CommandSender s, String l, String[] a) {
        String sub = a.length == 0 ? "info" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join" -> {
                if (!(s instanceof Player p)) return;
                if (active instanceof Tournament t) t.join(p);
                else if (active != null && active.location() != null) plugin.teleport().teleport(p, active.location(), active.name);
                else Msg.send(s, "event.none", "{prefix}&7Gerade laeuft kein Event. Naechste Events: &f/event info");
            }
            case "top" -> {
                Msg.send(s, "event.top-header", "&8&m-----&r &6&lEvent-Rangliste &8&m-----");
                List<Map.Entry<String, Integer>> list = new ArrayList<>(wins.entrySet());
                list.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
                for (int i = 0; i < Math.min(10, list.size()); i++)
                    Msg.send(s, "event.top-line", "&6#{rank} &f{name} &8- &e{wins} Siege", "rank", String.valueOf(i + 1), "name", list.get(i).getKey(), "wins", String.valueOf(list.get(i).getValue()));
            }
            case "community" -> {
                ConfigurationSection c = cfg.getConfigurationSection("community");
                if (c == null) return;
                long goal = c.getLong("goal", 100000);
                Msg.send(s, "event.community", "{prefix}&bCommunity-Challenge: &f{name}\n&7Fortschritt: &f{p}&7/&f{goal} {bar}",
                        "name", c.getString("name", "?"), "p", Text.money(communityProgress), "goal", Text.money(goal), "bar", Text.progressBar(communityProgress / (double) goal, 20, "&b", "&8"));
            }
            default -> {
                if (active != null) Msg.send(s, "event.active", "{prefix}&7Aktives Event: &b{name} &8- &f/event join", "name", active.name);
                else Msg.send(s, "event.none-active", "{prefix}&7Kein Event aktiv.");
                Msg.send(s, "event.schedule-header", "&7Zeitplan:");
                for (String e : cfg.getStringList("schedule")) {
                    String[] p = e.split("\\s+");
                    if (p.length >= 3) s.sendMessage(Text.c("&8» &f" + dayDe(p[0]) + " " + p[1] + " &7- &b" + (p[2].equalsIgnoreCase("random") ? "Zufalls-Event" : Text.strip(name(p[2])))));
                }
                SeasonalInfo si = seasonal();
                if (si != null) Msg.send(s, "event.seasonal", "{prefix}&dSaison-Event aktiv: &f{name} &7(x{m} Geld)", "name", si.name, "m", String.valueOf(si.money));
            }
        }
    }

    private static String dayDe(String d) {
        return switch (d.toUpperCase()) {
            case "MONDAY" -> "Mo"; case "TUESDAY" -> "Di"; case "WEDNESDAY" -> "Mi"; case "THURSDAY" -> "Do";
            case "FRIDAY" -> "Fr"; case "SATURDAY" -> "Sa"; case "SUNDAY" -> "So"; default -> "Taeglich";
        };
    }

    private void adminCmd(CommandSender s, String l, String[] a) {
        if (a.length == 0) { Msg.send(s, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/eventadmin start <id> | stop | list | setkoth <1|2> | setboss"); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (a.length < 2) return;
                if (active != null) { Msg.send(s, "event.already", "{prefix}&cEs laeuft bereits ein Event."); return; }
                String err = start(a[1], s);
                if (err != null) Msg.send(s, "event.start-error", "{prefix}&c{err}", "err", err);
                else plugin.audit().log(s, "EVENT_START", a[1]);
            }
            case "stop" -> { stop(false); plugin.audit().log(s, "EVENT_STOP", "-"); Msg.send(s, "event.stopped", "{prefix}&7Event gestoppt."); }
            case "list" -> Msg.send(s, "event.list", "{prefix}&7Events: &f{list}", "list", String.join(", ", eventIds()));
            case "setkoth" -> {
                if (!(s instanceof Player p) || a.length < 2) return;
                cfg.set("koth-region.pos" + a[1], PlayerData.locToString(p.getLocation()));
                saveCfg();
                Msg.send(p, "event.koth-set", "{prefix}&aKOTH-Ecke {n} gesetzt.", "n", a[1]);
            }
            case "setboss" -> {
                if (!(s instanceof Player p)) return;
                cfg.set("boss-spawn", PlayerData.locToString(p.getLocation()));
                saveCfg();
                Msg.send(p, "event.boss-set", "{prefix}&aBoss-Spawn gesetzt.");
            }
            default -> { }
        }
    }

    private void saveCfg() {
        try {
            cfg.save(new java.io.File(plugin.getDataFolder(), "events.yml"));
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("events.yml: " + e.getMessage());
        }
    }

    public String start(String id, CommandSender by) {
        ConfigurationSection s = cfg.getConfigurationSection("events." + id);
        if (s == null) return "Unbekanntes Event: " + id;
        if (active != null) return "Es laeuft bereits ein Event.";
        if (Bukkit.getOnlinePlayers().size() < s.getInt("min-players", 1)) return "Zu wenige Spieler online.";
        GameEvent ev = switch (s.getString("type", "").toUpperCase(Locale.ROOT)) {
            case "KOTH" -> new Koth(id, s);
            case "SUPPLY_DROP" -> new ChestHunt(id, s, false);
            case "TREASURE_HUNT" -> new ChestHunt(id, s, true);
            case "COMPETITION" -> new Competition(id, s);
            case "BOSS" -> new Boss(id, s);
            case "TOURNAMENT" -> new Tournament(id, s);
            default -> null;
        };
        if (ev == null) return "Unbekannter Event-Typ.";
        String err = ev.begin();
        if (err != null) return err;
        active = ev;
        plugin.webhook().send("events", "Event gestartet: " + Text.strip(ev.name), "Gestartet von " + by.getName(), 0x00BCD4);
        return null;
    }

    public void stop(boolean finished) {
        if (active == null) return;
        GameEvent ev = active;
        active = null;
        ev.end(finished);
        for (Player p : Bukkit.getOnlinePlayers()) plugin.scoreboard().hideBossbar(p, "event");
    }

    public void stopAll() {
        stop(false);
    }

    public record NextEvent(String name, long inMillis) {}

    /** Naechstes geplantes Event aus dem Zeitplan (innerhalb der naechsten 7 Tage). */
    public NextEvent nextEvent() {
        LocalDateTime now = LocalDateTime.now(RewardService.ZONE);
        NextEvent best = null;
        for (String entry : cfg.getStringList("schedule")) {
            String[] p = entry.trim().split("\\s+");
            if (p.length < 3) continue;
            String[] hm = p[1].split(":");
            if (hm.length != 2) continue;
            for (int d = 0; d <= 7; d++) {
                LocalDateTime t;
                try { t = now.toLocalDate().plusDays(d).atTime(Integer.parseInt(hm[0]), Integer.parseInt(hm[1])); } catch (Exception e) { break; }
                if (!t.isAfter(now)) continue;
                if (!p[0].equalsIgnoreCase("DAILY") && !p[0].equalsIgnoreCase(t.getDayOfWeek().name())) continue;
                long ms = java.time.Duration.between(now, t).toMillis();
                if (best == null || ms < best.inMillis()) best = new NextEvent(p[2].equalsIgnoreCase("random") ? "Zufalls-Event" : Text.strip(name(p[2])), ms);
                break;
            }
        }
        return best;
    }

    public String activeName() {
        return active == null ? null : Text.strip(active.name);
    }

    private void winner(Player p, int place, ConfigurationSection eventCfg) {
        ConfigurationSection r = eventCfg.getConfigurationSection("rewards." + place);
        if (r != null) plugin.rewards().give(p, r, Text.strip(eventCfg.getString("name", "Event")) + " Platz " + place);
        if (place == 1) {
            plugin.data().get(p).eventWins++;
            plugin.data().get(p).dirty = true;
            wins.merge(p.getName(), 1, Integer::sum);
            YamlConfiguration y = new YamlConfiguration();
            wins.forEach(y::set);
            plugin.db().kvSet("event-wins", y.saveToString());
        }
    }

    private void bossbarAll(String text, double progress, BarColor color) {
        for (Player p : Bukkit.getOnlinePlayers()) plugin.scoreboard().bossbar(p, "event", text, progress, color);
    }

    // ---------------- Hooks fuer andere Systeme ----------------

    public void onAction(Player p, String type, int amount) {
        if (active instanceof Competition c) c.add(p, type, amount);
        ConfigurationSection cc = cfg.getConfigurationSection("community");
        if (cc != null && type.equals(cc.getString("type", "COMMUNITY_MINING"))) {
            long goal = cc.getLong("goal", 100000);
            if (communityProgress >= goal) return;
            communityProgress += amount;
            if (communityProgress % 500 == 0 || communityProgress >= goal) plugin.db().kvSet("community-progress", String.valueOf(communityProgress));
            if (communityProgress >= goal) {
                Msg.broadcast("event.community-done", "{prefix}&b&lCOMMUNITY-CHALLENGE GESCHAFFT! &7Alle Online-Spieler erhalten eine Belohnung!");
                for (Player o : Bukkit.getOnlinePlayers()) plugin.rewards().give(o, cc.getConfigurationSection("reward"), "Community-Challenge");
                communityProgress = 0; // naechste Runde
                plugin.db().kvSet("community-progress", "0");
            }
        }
    }

    public void onMobKill(Player p, LivingEntity e) {
        if (active instanceof Boss b) b.onDeath(e);
    }

    public long communityProgress() { return communityProgress; }

    // ---------------- Saisonale Events ----------------

    public record SeasonalInfo(String name, double money, String joinMessage) {}

    public SeasonalInfo seasonal() {
        ConfigurationSection s = cfg.getConfigurationSection("seasonal");
        if (s == null) return null;
        MonthDay today = MonthDay.now(RewardService.ZONE);
        for (String k : s.getKeys(false)) {
            ConfigurationSection e = s.getConfigurationSection(k);
            try {
                MonthDay from = MonthDay.parse("--" + e.getString("from")), to = MonthDay.parse("--" + e.getString("to"));
                boolean in = from.isBefore(to) || from.equals(to) ? !today.isBefore(from) && !today.isAfter(to) : !today.isBefore(from) || !today.isAfter(to);
                if (in) return new SeasonalInfo(e.getString("name", k), e.getDouble("money-multiplier", 1.0), e.getString("join-message", ""));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public double moneyMultiplier() {
        SeasonalInfo s = seasonal();
        return s == null ? 1.0 : s.money;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        SeasonalInfo s = seasonal();
        if (s != null && !s.joinMessage.isEmpty()) Bukkit.getScheduler().runTaskLater(plugin, () -> e.getPlayer().sendMessage(Text.c(s.joinMessage)), 40);
    }

    // =====================================================================
    // Event-Typen
    // =====================================================================

    private abstract class GameEvent {
        final String id, name;
        final ConfigurationSection s;
        final long endAt;
        GameEvent(String id, ConfigurationSection s) {
            this.id = id;
            this.s = s;
            this.name = s.getString("name", id);
            this.endAt = System.currentTimeMillis() + s.getLong("duration-minutes", 15) * 60_000;
        }
        abstract String begin();
        abstract void tick();
        abstract void end(boolean finished);
        Location location() { return null; }
        long left() { return Math.max(0, endAt - System.currentTimeMillis()); }
    }

    /** King of the Hill: Wer (alleine bzw. nur ein Clan) am laengsten im Bereich steht, gewinnt. */
    private final class Koth extends GameEvent {
        Location a, b;
        final Map<UUID, Integer> time = new HashMap<>();
        final int need;
        Koth(String id, ConfigurationSection s) { super(id, s); need = s.getInt("capture-seconds", 180); }

        @Override String begin() {
            a = PlayerData.locFromString(cfg.getString("koth-region.pos1"));
            b = PlayerData.locFromString(cfg.getString("koth-region.pos2"));
            if (a == null || b == null || a.getWorld() != b.getWorld()) return "KOTH-Bereich nicht gesetzt (/eventadmin setkoth 1|2).";
            Msg.broadcast("koth.start", "{prefix}&6&lKING OF THE HILL &7hat begonnen! Halte den Huegel &f{s}s&7. &f/event join", "s", String.valueOf(need));
            return null;
        }

        boolean inside(Location l) {
            return l.getWorld() == a.getWorld()
                    && l.getX() >= Math.min(a.getX(), b.getX()) && l.getX() <= Math.max(a.getX(), b.getX()) + 1
                    && l.getY() >= Math.min(a.getY(), b.getY()) - 1 && l.getY() <= Math.max(a.getY(), b.getY()) + 3
                    && l.getZ() >= Math.min(a.getZ(), b.getZ()) && l.getZ() <= Math.max(a.getZ(), b.getZ()) + 1;
        }

        @Override Location location() { return a == null ? null : a.clone().add(b.clone().subtract(a).multiply(0.5)); }

        @Override void tick() {
            List<Player> in = new ArrayList<>();
            for (Player p : a.getWorld().getPlayers()) if (!p.isDead() && p.getGameMode() == org.bukkit.GameMode.SURVIVAL && inside(p.getLocation())) in.add(p);
            Player capper = null;
            boolean contested = false;
            if (!in.isEmpty()) {
                Clan first = plugin.clans().of(in.get(0));
                for (Player p : in) {
                    Clan c = plugin.clans().of(p);
                    if (in.size() > 1 && (first == null || c != first)) { contested = true; break; }
                }
                if (!contested) capper = in.get(0);
            }
            if (capper != null) {
                int t = time.merge(capper.getUniqueId(), 1, Integer::sum);
                if (t >= need) {
                    Msg.broadcast("koth.win", "{prefix}&6&l{name} &7hat KOTH gewonnen!", "name", capper.getName());
                    winner(capper, 1, s);
                    stop(true);
                    return;
                }
                bossbarAll("&6&lKOTH &8| &f" + capper.getName() + " &7erobert &8(" + t + "/" + need + "s)", t / (double) need, BarColor.YELLOW);
            } else {
                bossbarAll(contested ? "&6&lKOTH &8| &cUmkaempft!" : "&6&lKOTH &8| &7Niemand auf dem Huegel &8| " + TimeUtil.format(left()), left() / (double) (s.getLong("duration-minutes", 15) * 60_000), BarColor.YELLOW);
            }
            a.getWorld().spawnParticle(Particle.DUST, location().add(0, 2, 0), 10, 1.5, 0.5, 1.5, 0, new Particle.DustOptions(Color.ORANGE, 1.5f));
            if (left() <= 0) {
                UUID best = time.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
                Player bp = best == null ? null : Bukkit.getPlayer(best);
                if (bp != null) {
                    Msg.broadcast("koth.timeout-win", "{prefix}&7Zeit abgelaufen! &6{name} &7hatte die meiste Zeit und gewinnt.", "name", bp.getName());
                    winner(bp, 1, s);
                } else Msg.broadcast("koth.no-winner", "{prefix}&7KOTH endet ohne Gewinner.");
                stop(true);
            }
        }

        @Override void end(boolean finished) { }
    }

    /** Supply Drop (Koordinaten bekannt) oder Schatzsuche (nur Hinweise). Erster Oeffner gewinnt. */
    private final class ChestHunt extends GameEvent {
        final boolean treasure;
        Location chest;
        int hint;
        ChestHunt(String id, ConfigurationSection s, boolean treasure) { super(id, s); this.treasure = treasure; }

        @Override String begin() {
            World w = Bukkit.getWorld(s.getString("world", "world"));
            if (w == null) return "Welt nicht gefunden.";
            int r = s.getInt("radius", 1500);
            Location c = w.getWorldBorder().getCenter();
            int x = c.getBlockX() + ThreadLocalRandom.current().nextInt(-r, r + 1), z = c.getBlockZ() + ThreadLocalRandom.current().nextInt(-r, r + 1);
            w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(ch -> {
                Block top = w.getHighestBlockAt(x, z);
                Block target = treasure ? top.getRelative(0, -2, 0) : top.getRelative(0, 1, 0);
                if (treasure && !target.getType().isSolid()) target = top.getRelative(0, 1, 0);
                target.setType(Material.CHEST);
                if (target.getState() instanceof Chest chestState) {
                    chestState.getPersistentDataContainer().set(eventKey, PersistentDataType.STRING, id);
                    chestState.customName(Text.c(name));
                    chestState.update();
                }
                if (target.getState(false) instanceof Chest live) fill(live);
                chest = target.getLocation();
                if (!treasure) {
                    Msg.broadcast("supply.start", "{prefix}&b&lSUPPLY DROP &7bei &fX: {x} Z: {z} &7in &f{world}&7! Schnell sein!", "x", String.valueOf(x), "z", String.valueOf(z), "world", w.getName());
                    Firework fw = w.spawn(chest.clone().add(0.5, 1, 0.5), Firework.class);
                    var meta = fw.getFireworkMeta();
                    meta.addEffect(FireworkEffect.builder().withColor(Color.AQUA, Color.WHITE).with(FireworkEffect.Type.BALL_LARGE).build());
                    meta.setPower(1);
                    fw.setFireworkMeta(meta);
                } else {
                    Msg.broadcast("treasure.start", "{prefix}&e&lSCHATZSUCHE! &7Ein Schatz wurde in &f{world} &7vergraben. Hinweise folgen!", "world", w.getName());
                }
            });
            return null;
        }

        void fill(Chest c) {
            List<ItemStack> loot = new ArrayList<>();
            ConfigurationSection l = s.getConfigurationSection("loot");
            if (l != null) {
                for (String k : l.getKeys(false)) {
                    ConfigurationSection e = l.getConfigurationSection(k);
                    if (ThreadLocalRandom.current().nextDouble(100) < e.getDouble("chance", 50)) loot.add(Items.fromConfig(e));
                }
            }
            for (ItemStack it : loot) {
                int slot = ThreadLocalRandom.current().nextInt(c.getBlockInventory().getSize());
                if (c.getBlockInventory().getItem(slot) == null) c.getBlockInventory().setItem(slot, it);
                else c.getBlockInventory().addItem(it);
            }
        }

        @Override Location location() { return treasure || chest == null ? null : chest.clone().add(0.5, 1, 0.5); }

        @Override void tick() {
            if (chest == null) return;
            long elapsed = s.getLong("duration-minutes", 15) * 60_000 - left();
            if (!treasure) {
                chest.getWorld().spawnParticle(Particle.END_ROD, chest.clone().add(0.5, 1.5, 0.5), 5, 0.2, 3, 0.2, 0.02);
                bossbarAll("&b&lSUPPLY DROP &8| &fX " + chest.getBlockX() + " Z " + chest.getBlockZ() + " &8| &7" + TimeUtil.format(left()), left() / (double) (s.getLong("duration-minutes", 15) * 60_000), BarColor.BLUE);
            } else {
                int interval = s.getInt("hint-interval-seconds", 120);
                if (elapsed / 1000 >= (long) hint * interval) {
                    int[] radii = {600, 300, 150, 75, 30, 15};
                    int rad = radii[Math.min(hint, radii.length - 1)];
                    int ox = chest.getBlockX() + ThreadLocalRandom.current().nextInt(-rad / 2, rad / 2 + 1);
                    int oz = chest.getBlockZ() + ThreadLocalRandom.current().nextInt(-rad / 2, rad / 2 + 1);
                    Msg.broadcast("treasure.hint", "{prefix}&eHinweis #{n}: &7Der Schatz liegt im Umkreis von &f{r} &7Bloecken um &fX {x} Z {z}&7.",
                            "n", String.valueOf(hint + 1), "r", String.valueOf(rad), "x", String.valueOf(ox), "z", String.valueOf(oz));
                    hint++;
                }
                bossbarAll("&e&lSCHATZSUCHE &8| &7Verbleibend: &f" + TimeUtil.format(left()), left() / (double) (s.getLong("duration-minutes", 15) * 60_000), BarColor.YELLOW);
            }
            if (left() <= 0) {
                Msg.broadcast("hunt.timeout", "{prefix}&7{name} &7ist abgelaufen - niemand hat die Kiste gefunden.", "name", name);
                stop(false);
            }
        }

        void opened(Player p) {
            Msg.broadcast("hunt.found", "{prefix}&f{player} &7hat die Kiste von &b{name} &7gefunden!", "player", p.getName(), "name", name);
            winner(p, 1, s);
            stop(true);
        }

        @Override void end(boolean finished) {
            if (chest == null) return;
            Location c = chest;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (c.getBlock().getState() instanceof Chest ch && ch.getPersistentDataContainer().has(eventKey)) {
                    ch.getBlockInventory().clear();
                    c.getBlock().setType(Material.AIR);
                }
            }, finished ? 20L * 60 : 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Chest c) || !(e.getPlayer() instanceof Player p)) return;
        if (!c.getPersistentDataContainer().has(eventKey)) return;
        if (active instanceof ChestHunt h && h.chest != null && h.chest.equals(c.getLocation())) h.opened(p);
    }

    /** Wettbewerb: Wer in der Zeit am meisten X schafft (MINING, FISHING, FARMING, WOODCUTTING). */
    private final class Competition extends GameEvent {
        final String type;
        final Map<UUID, Integer> score = new HashMap<>();
        Competition(String id, ConfigurationSection s) { super(id, s); type = s.getString("action", "MINING").toUpperCase(Locale.ROOT); }

        @Override String begin() {
            Msg.broadcast("competition.start", "{prefix}&a&l{name} &7hat begonnen! &f{min} Minuten &7- die Besten gewinnen!", "name", name, "min", String.valueOf(s.getLong("duration-minutes", 15)));
            return null;
        }

        void add(Player p, String t, int n) {
            if (t.equals(type)) score.merge(p.getUniqueId(), n, Integer::sum);
        }

        List<Map.Entry<UUID, Integer>> ranking() {
            List<Map.Entry<UUID, Integer>> l = new ArrayList<>(score.entrySet());
            l.sort(Map.Entry.<UUID, Integer>comparingByValue().reversed());
            return l;
        }

        @Override void tick() {
            List<Map.Entry<UUID, Integer>> r = ranking();
            String lead = r.isEmpty() ? "-" : Bukkit.getOfflinePlayer(r.get(0).getKey()).getName() + " (" + r.get(0).getValue() + ")";
            for (Player p : Bukkit.getOnlinePlayers()) {
                plugin.scoreboard().bossbar(p, "event", Text.strip(name) + " &8| &7Fuehrend: &f" + lead + " &8| &7Du: &b" + score.getOrDefault(p.getUniqueId(), 0)
                        + " &8| &7" + TimeUtil.format(left()), left() / (double) (s.getLong("duration-minutes", 15) * 60_000), BarColor.GREEN);
            }
            if (left() <= 0) stop(true);
        }

        @Override void end(boolean finished) {
            if (!finished) return;
            List<Map.Entry<UUID, Integer>> r = ranking();
            Msg.broadcast("competition.end", "{prefix}&a&l{name} &7ist vorbei! Ergebnisse:", "name", name);
            for (int i = 0; i < Math.min(3, r.size()); i++) {
                Player p = Bukkit.getPlayer(r.get(i).getKey());
                String n = p != null ? p.getName() : Bukkit.getOfflinePlayer(r.get(i).getKey()).getName();
                Msg.broadcast("competition.place", "&8» &6#{rank} &f{name} &7- &b{score}", "rank", String.valueOf(i + 1), "name", n, "score", String.valueOf(r.get(i).getValue()));
                if (p != null) winner(p, i + 1, s);
            }
            ConfigurationSection part = s.getConfigurationSection("rewards.participation");
            for (int i = 3; i < r.size(); i++) {
                Player p = Bukkit.getPlayer(r.get(i).getKey());
                if (p != null && r.get(i).getValue() >= s.getInt("participation-min", 10)) plugin.rewards().give(p, part, "Teilnahme");
            }
        }
    }

    /** Boss-Event: Starker Mob, Bossbar mit Lebenspunkten, Belohnung nach Schaden. */
    private final class Boss extends GameEvent {
        LivingEntity boss;
        final Map<UUID, Double> damage = new HashMap<>();
        double maxHp;
        Boss(String id, ConfigurationSection s) { super(id, s); }

        @Override String begin() {
            Location l = PlayerData.locFromString(cfg.getString("boss-spawn"));
            if (l == null) return "Boss-Spawn nicht gesetzt (/eventadmin setboss).";
            EntityType type;
            try { type = EntityType.valueOf(s.getString("entity", "WITHER_SKELETON").toUpperCase()); } catch (IllegalArgumentException e) { return "Ungueltiger Boss-Typ."; }
            maxHp = s.getDouble("health", 500);
            boss = (LivingEntity) l.getWorld().spawnEntity(l, type, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, en -> {
                LivingEntity le = (LivingEntity) en;
                var hp = le.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null) hp.setBaseValue(maxHp);
                le.setHealth(maxHp);
                var dmg = le.getAttribute(Attribute.ATTACK_DAMAGE);
                if (dmg != null) dmg.setBaseValue(s.getDouble("damage", 12));
                le.customName(Text.c(name));
                le.setCustomNameVisible(true);
                le.setRemoveWhenFarAway(false);
                le.getPersistentDataContainer().set(eventKey, PersistentDataType.STRING, "boss");
                if (le.getEquipment() != null) {
                    le.getEquipment().setItem(EquipmentSlot.HEAD, new ItemStack(Material.NETHERITE_HELMET));
                    le.getEquipment().setItem(EquipmentSlot.HAND, new ItemStack(Material.NETHERITE_SWORD));
                    le.getEquipment().setDropChance(EquipmentSlot.HEAD, 0);
                    le.getEquipment().setDropChance(EquipmentSlot.HAND, 0);
                }
            });
            Msg.broadcast("boss.start", "{prefix}&4&lBOSS-EVENT! {name} &7ist erschienen! &f/event join", "name", name);
            return null;
        }

        @Override Location location() { return boss == null ? null : boss.getLocation(); }

        @Override void tick() {
            if (boss == null) return;
            if (!boss.isValid() && !boss.isDead()) { stop(false); return; }
            bossbarAll(name + " &8| &c" + (int) boss.getHealth() + "&7/&c" + (int) maxHp + " ❤ &8| &7" + TimeUtil.format(left()), boss.getHealth() / maxHp, BarColor.RED);
            if (left() <= 0) {
                Msg.broadcast("boss.escaped", "{prefix}&7Der Boss ist entkommen...");
                stop(false);
            }
        }

        void hit(Player p, double d) { damage.merge(p.getUniqueId(), d, Double::sum); }

        void onDeath(LivingEntity e) {
            if (e != boss) return;
            List<Map.Entry<UUID, Double>> r = new ArrayList<>(damage.entrySet());
            r.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
            Msg.broadcast("boss.dead", "{prefix}&a&lDer Boss wurde besiegt! &7Top-Schaden:");
            for (int i = 0; i < r.size(); i++) {
                Player p = Bukkit.getPlayer(r.get(i).getKey());
                if (p == null) continue;
                if (i < 3) {
                    Msg.broadcast("boss.place", "&8» &6#{rank} &f{name} &7- &c{dmg} Schaden", "rank", String.valueOf(i + 1), "name", p.getName(), "dmg", String.valueOf(Math.round(r.get(i).getValue())));
                    winner(p, i + 1, s);
                } else plugin.rewards().give(p, s.getConfigurationSection("rewards.participation"), "Boss-Teilnahme");
            }
            stop(true);
        }

        @Override void end(boolean finished) {
            if (boss != null && boss.isValid()) boss.remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDamage(EntityDamageByEntityEvent e) {
        if (!(active instanceof Boss b) || e.getEntity() != b.boss) return;
        Player p = e.getDamager() instanceof Player pl ? pl : e.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player pl2 ? pl2 : null;
        if (p != null) b.hit(p, e.getFinalDamage());
    }

    @EventHandler
    public void onBossDrops(EntityDeathEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(eventKey)) { e.getDrops().clear(); e.setDroppedExp(0); }
    }

    /** PvP-Turnier: Anmeldung, dann K.-o.-System ueber das Duell-System (nacheinander). */
    private final class Tournament extends GameEvent {
        final List<UUID> joined = new ArrayList<>();
        List<UUID> round = new ArrayList<>(), next = new ArrayList<>();
        final long signupEnd;
        boolean running, matchRunning;
        int roundNo = 1;
        Tournament(String id, ConfigurationSection s) {
            super(id, s);
            signupEnd = System.currentTimeMillis() + s.getLong("signup-seconds", 90) * 1000;
        }

        @Override String begin() {
            if (plugin.kits().get(s.getString("kit", "duel_classic")) == null) return "Turnier-Kit nicht gefunden.";
            Msg.broadcast("tournament.start", "{prefix}&c&lPVP-TURNIER! &7Anmeldung offen fuer &f{s}s&7: &f/event join", "s", String.valueOf(s.getLong("signup-seconds", 90)));
            return null;
        }

        void join(Player p) {
            if (running) { Msg.send(p, "tournament.closed", "{prefix}&cDie Anmeldung ist geschlossen."); return; }
            if (joined.contains(p.getUniqueId())) { Msg.send(p, "tournament.already", "{prefix}&7Du bist bereits angemeldet."); return; }
            joined.add(p.getUniqueId());
            Msg.broadcast("tournament.joined", "{prefix}&f{name} &7nimmt am Turnier teil &8({n})", "name", p.getName(), "n", String.valueOf(joined.size()));
        }

        @Override void tick() {
            if (!running) {
                bossbarAll("&c&lPVP-TURNIER &8| &7Anmeldung: &f" + TimeUtil.format(signupEnd - System.currentTimeMillis()) + " &8| &f" + joined.size() + " Spieler",
                        Math.max(0, (signupEnd - System.currentTimeMillis()) / (s.getLong("signup-seconds", 90) * 1000.0)), BarColor.RED);
                if (System.currentTimeMillis() >= signupEnd) {
                    joined.removeIf(u -> Bukkit.getPlayer(u) == null);
                    if (joined.size() < s.getInt("min-participants", 2)) {
                        Msg.broadcast("tournament.cancel", "{prefix}&7Zu wenige Teilnehmer - Turnier abgesagt.");
                        stop(false);
                        return;
                    }
                    running = true;
                    round = new ArrayList<>(joined);
                    java.util.Collections.shuffle(round);
                    Msg.broadcast("tournament.begin", "{prefix}&c&lDas Turnier beginnt mit &f{n} &c&lSpielern!", "n", String.valueOf(round.size()));
                }
                return;
            }
            if (matchRunning) return;
            round.removeIf(u -> Bukkit.getPlayer(u) == null);
            if (round.size() + next.size() == 1) {
                Player w = Bukkit.getPlayer(round.isEmpty() ? next.get(0) : round.get(0));
                if (w != null) {
                    Msg.broadcast("tournament.win", "{prefix}&6&l{name} &7gewinnt das PvP-Turnier!", "name", w.getName());
                    winner(w, 1, s);
                }
                stop(true);
                return;
            }
            if (round.isEmpty() && next.isEmpty()) { stop(false); return; }
            if (round.size() < 2) {
                next.addAll(round);
                round = next;
                next = new ArrayList<>();
                roundNo++;
                Msg.broadcast("tournament.round", "{prefix}&cRunde {n} &7- &f{p} Spieler uebrig", "n", String.valueOf(roundNo), "p", String.valueOf(round.size()));
                return;
            }
            Player a = Bukkit.getPlayer(round.remove(0)), b = Bukkit.getPlayer(round.remove(0));
            if (a == null || b == null) { if (a != null) next.add(a.getUniqueId()); if (b != null) next.add(b.getUniqueId()); return; }
            matchRunning = true;
            Msg.broadcast("tournament.match", "{prefix}&7Match: &f{a} &cvs &f{b}", "a", a.getName(), "b", b.getName());
            String err = plugin.duels().start(a, b, plugin.kits().get(s.getString("kit", "duel_classic")), (win, lose) -> {
                matchRunning = false;
                Player adv = win != null ? win : a.isOnline() ? a : b;
                next.add(adv.getUniqueId());
                if (lose != null) plugin.rewards().give(lose, s.getConfigurationSection("rewards.participation"), "Turnier-Teilnahme");
            });
            if (err != null) {
                matchRunning = false;
                next.add(a.getUniqueId());
                next.add(b.getUniqueId());
                Msg.broadcast("tournament.error", "{prefix}&cMatch konnte nicht starten: {err}", "err", err);
                stop(false);
            }
        }

        @Override void end(boolean finished) { }
    }
}
