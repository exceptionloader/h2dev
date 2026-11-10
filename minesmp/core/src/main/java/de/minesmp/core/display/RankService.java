package de.minesmp.core.display;

import de.minesmp.core.MineSMP;
import de.minesmp.core.clan.Clan;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Raenge (ranks.yml): Abzeichen, Namensfarbe, eigenes Namensschild als TextDisplay auf dem Spieler
 * (dadurch auch selbst in F5 sichtbar) und Rang-Partikel.
 * Pro Spieler gibt es zwei Namensschilder: mit Abzeichen-Grafik (fuer Spieler mit Ressourcenpaket)
 * und als Text (ohne Paket). Jeder Betrachter sieht nur das passende.
 */
public final class RankService implements Listener {
    public record Rank(String id, String badge, String text, String nameFormat, String style, Color c1, Color c2, String icon, boolean verified) {}

    private record Pt(Particle particle, Location loc, Object data) {}

    private static final class Tag {
        TextDisplay pack, text;
        String lastPack = "", lastText = "";
    }

    private final MineSMP plugin;
    private final NamespacedKey tagKey;
    private final List<Rank> ranks = new ArrayList<>();
    private Rank fallback;
    private boolean tagsEnabled, particlesEnabled;
    private String line1Pack, line1, line2, line2Pvp, clanFormat, afkFormat, checkPack, checkText;
    private float height, viewRange;
    private int range;
    private final Map<UUID, Tag> tags = new HashMap<>();
    private long tick;
    private org.bukkit.scheduler.BukkitTask particleTask;

    public RankService(MineSMP plugin) {
        this.plugin = plugin;
        this.tagKey = new NamespacedKey(plugin, "nametag");
        reload();
        plugin.listen(this);
        // Reste nach einem Absturz entfernen
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getWorlds().forEach(w -> w.getEntitiesByClass(TextDisplay.class).forEach(e -> {
            if (e.getPersistentDataContainer().has(tagKey)) e.remove();
        })));
        Bukkit.getScheduler().runTaskTimer(plugin, this::syncTags, 20, 10);
    }

    private static Color color(String hex, Color def) {
        try {
            return Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16));
        } catch (Exception e) {
            return def;
        }
    }

    public void reload() {
        YamlConfiguration y = plugin.yaml("ranks.yml");
        ranks.clear();
        ConfigurationSection rs = y.getConfigurationSection("ranks");
        if (rs != null) {
            for (String id : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(id);
                List<String> cols = r.getStringList("colors");
                Rank rank = new Rank(id.toLowerCase(Locale.ROOT), r.getString("badge", "rank_spieler"), r.getString("text", "&7" + id),
                        r.getString("name", "&f{name}"), r.getString("particles", "none").toLowerCase(Locale.ROOT),
                        color(cols.isEmpty() ? "#FFFFFF" : cols.get(0), Color.WHITE), color(cols.size() < 2 ? "#FFFFFF" : cols.get(1), Color.WHITE),
                        r.getString("icon", r.getString("badge", "rank_spieler").replace("rank_", "icon_")), r.getBoolean("verified", !id.equalsIgnoreCase("default")));
                if (rank.id.equals("default")) fallback = rank;
                else ranks.add(rank);
            }
        }
        if (fallback == null) fallback = new Rank("default", "rank_spieler", "&7Spieler", "&f{name}", "none", Color.WHITE, Color.WHITE, "icon_spieler", false);
        tagsEnabled = y.getBoolean("nametag.enabled", true);
        line1Pack = y.getString("nametag.line1-pack", "{icon}{check}{badge} &8| &f{player}{clan}{afk}");
        line1 = y.getString("nametag.line1", "{check}{text} &8| &f{player}{clan}{afk}");
        line2 = y.getString("nametag.line2", "&7Coins: &b%balance%&7, Gems: &b%gems%");
        line2Pvp = y.getString("nametag.line2-pvp", "&c%kd% K/D &8| &d%ping%ms");
        clanFormat = y.getString("nametag.clan-format", " &8[&b{tag}&8]");
        checkPack = y.getString("check.pack", "&8[&f:check:&8] ");
        checkText = y.getString("check.text", "&8[&a\u2714&8] ");
        afkFormat = y.getString("nametag.afk-format", " &7[AFK]");
        height = (float) y.getDouble("nametag.height", 0.28);
        viewRange = (float) y.getDouble("nametag.view-range", 0.7);
        particlesEnabled = y.getBoolean("particles.enabled", true);
        range = y.getInt("particles.range", 32);
        if (particleTask != null) particleTask.cancel();
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::particles, 40, Math.max(1, y.getInt("particles.interval-ticks", 2)));
        for (Tag t : tags.values()) { t.lastPack = ""; t.lastText = ""; }
        if (!tagsEnabled) for (UUID id : new ArrayList<>(tags.keySet())) removeTag(id);
    }

    public boolean nametagsEnabled() {
        return tagsEnabled;
    }

    /** Rang eines Spielers: hoechster Rang, dessen LuckPerms-Gruppe gesetzt ist (OP allein zaehlt nicht). */
    public Rank rankOf(Player p) {
        for (Rank r : ranks) {
            String perm = "group." + r.id;
            if (p.isPermissionSet(perm) && p.hasPermission(perm)) return r;
        }
        return fallback;
    }

    /** Position in der Rangliste (0 = hoechster Rang), fuer die Tab-Sortierung. */
    public int order(Player p) {
        Rank r = rankOf(p);
        int i = ranks.indexOf(r);
        return i < 0 ? ranks.size() : i;
    }

    public String badge(Player p) {
        String g = plugin.pack() == null ? null : plugin.pack().glyphs(":" + rankOf(p).badge + ":");
        return g == null ? rankOf(p).text : g;
    }

    public String rankText(Player p) {
        return rankOf(p).text;
    }

    /** Spielername im Rang-Format (Farbe/Verlauf). */
    public String name(Player p) {
        return Text.replace(rankOf(p).nameFormat, "name", p.getName());
    }

    // ================================================================ Namensschild
    /**
     * Ersetzt {icon} {check} {badge} {text} {name} {player} {clan} {afk} {level} und %platzhalter%.
     * pack = Variante fuer Spieler mit Ressourcenpaket (Abzeichen als Grafik), sonst reiner Text.
     */
    public String format(Player p, String fmt, boolean pack) {
        if (fmt == null || fmt.isEmpty()) return "";
        Rank r = rankOf(p);
        Clan c = plugin.clans().of(p);
        PlayerData d = plugin.data().getIfLoaded(p.getUniqueId());
        boolean packOk = pack && plugin.pack() != null;
        boolean verified = r.verified || p.hasPermission("minesmp.verified");
        String s = Text.replace(fmt,
                "icon", packOk && !r.icon.isEmpty() ? "&f:" + r.icon + ": " : "",
                "check", verified ? (packOk ? checkPack : checkText) : "",
                "badge", packOk ? "&f:" + r.badge + ":" : r.text,
                "text", r.text, "name", name(p), "player", p.getName(),
                "clan", c == null ? "" : Text.replace(clanFormat, "tag", c.tag),
                "afk", plugin.afk() != null && plugin.afk().isAfk(p) ? afkFormat : "",
                "level", d == null ? "1" : String.valueOf(d.level));
        if (d != null && s.indexOf('%') >= 0 && plugin.placeholders() != null) s = plugin.placeholders().apply(p, s);
        return packOk ? plugin.pack().glyphs(s) : s;
    }

    private String[] tagTexts(Player p) {
        String a = format(p, line1Pack, true), b = format(p, line1, false);
        // Im PvP-Bereich (Arena, Duell, Sumo) steht statt Coins die K/D und der Ping
        boolean pvp = !line2Pvp.isEmpty() && (plugin.duels().inDuel(p) || (plugin.spawn() != null && plugin.spawn().isPvpZone(p.getLocation())));
        String second = pvp ? line2Pvp : line2;
        String l2a = format(p, second, true), l2b = format(p, second, false);
        return new String[]{l2a.isBlank() ? a : a + "\n" + l2a, l2b.isBlank() ? b : b + "\n" + l2b};
    }

    private TextDisplay spawnTag(Player p) {
        return p.getWorld().spawn(p.getLocation(), TextDisplay.class, td -> {
            td.setPersistent(false);
            td.setVisibleByDefault(false);
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(!p.isSneaking());
            td.setShadowed(true);
            td.setDefaultBackground(false);
            td.setBackgroundColor(Color.fromARGB(0x40, 0, 0, 0));
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setLineWidth(300);
            td.setViewRange(viewRange);
            td.setTransformation(new Transformation(new Vector3f(0, height, 0), new AxisAngle4f(), new Vector3f(1, 1, 1), new AxisAngle4f()));
            td.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private TextDisplay mounted(Player p, TextDisplay d) {
        if (d != null && d.isValid() && d.getWorld().equals(p.getWorld()) && p.getPassengers().contains(d)) return d;
        if (d != null) d.remove();
        TextDisplay n = spawnTag(p);
        p.addPassenger(n);
        return n;
    }

    private boolean showTagOf(Player p) {
        if (!tagsEnabled || !p.isOnline() || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return false;
        // Im Vanish bleibt das Schild fuer das Team sichtbar
        if (plugin.vanish() != null && plugin.vanish().isVanished(p)) return true;
        return !p.isInvisible() && !p.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    private void syncTags() {
        if (!tagsEnabled) return;
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player p : online) syncTag(p);
        // Sichtbarkeit: jeder Betrachter sieht genau eine Variante (mit/ohne Ressourcenpaket)
        for (Player v : online) {
            boolean wantPack = plugin.pack() != null && plugin.pack().has(v);
            PlayerData vd = plugin.data().getIfLoaded(v.getUniqueId());
            for (Player o : online) {
                Tag t = tags.get(o.getUniqueId());
                if (t == null) continue;
                boolean fullVanish = plugin.vanish() != null && plugin.vanish().mode(o) == de.minesmp.core.admin.VanishService.Mode.FULL;
                boolean see = v.equals(o) ? !fullVanish && (vd == null || vd.setting("nametag-self", true)) : v.canSee(o) && v.getWorld().equals(o.getWorld());
                visible(v, t.pack, see && wantPack);
                visible(v, t.text, see && !wantPack);
            }
        }
    }

    private void visible(Player v, Entity e, boolean show) {
        if (e == null || !e.isValid()) return;
        if (show) v.showEntity(plugin, e); else v.hideEntity(plugin, e);
    }

    private void syncTag(Player p) {
        Long until = suspended.get(p.getUniqueId());
        if (until != null) {
            if (until > System.currentTimeMillis()) return; // Teleport in andere Welt laeuft
            suspended.remove(p.getUniqueId());
        }
        if (!showTagOf(p)) { removeTag(p.getUniqueId()); return; }
        Tag t = tags.computeIfAbsent(p.getUniqueId(), k -> new Tag());
        TextDisplay oldPack = t.pack, oldText = t.text;
        t.pack = mounted(p, t.pack);
        t.text = mounted(p, t.text);
        String[] tx = tagTexts(p);
        if (t.pack != oldPack || !tx[0].equals(t.lastPack)) { t.pack.text(Text.c(tx[0])); t.lastPack = tx[0]; }
        if (t.text != oldText || !tx[1].equals(t.lastText)) { t.text.text(Text.c(tx[1])); t.lastText = tx[1]; }
    }

    private void removeTag(UUID id) {
        Tag t = tags.remove(id);
        if (t == null) return;
        if (t.pack != null) t.pack.remove();
        if (t.text != null) t.text.remove();
    }

    private void soon(Player p, long ticks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) syncTags(); }, ticks);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { soon(e.getPlayer(), 20); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { removeTag(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) { removeTag(e.getEntity().getUniqueId()); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) { soon(e.getPlayer(), 2); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { removeTag(e.getPlayer().getUniqueId()); soon(e.getPlayer(), 2); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo().getWorld() != e.getFrom().getWorld()) removeTag(e.getPlayer().getUniqueId());
        soon(e.getPlayer(), 2);
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) { soon(e.getPlayer(), 1); }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        Tag t = tags.get(e.getPlayer().getUniqueId());
        if (t == null) return;
        for (TextDisplay d : new TextDisplay[]{t.pack, t.text}) {
            if (d == null || !d.isValid()) continue;
            d.setSeeThrough(!e.isSneaking());
            d.setTextOpacity(e.isSneaking() ? (byte) 0x70 : (byte) -1);
        }
    }

    // ================================================================ Teleports
    private final Map<UUID, Long> suspended = new HashMap<>();

    /** Paper verweigert Plugin-Teleports in andere Welten, solange etwas auf dem Spieler sitzt: Namensschild vorher abnehmen. */
    private void prepare(Player p, Location to) {
        if (to == null || to.getWorld() == null || to.getWorld().equals(p.getWorld())) return;
        suspended.put(p.getUniqueId(), System.currentTimeMillis() + 10_000);
        removeTag(p.getUniqueId());
    }

    /** Teleport (asynchron), der auch mit Namensschild in andere Welten funktioniert. */
    public java.util.concurrent.CompletableFuture<Boolean> teleport(Player p, Location to) {
        prepare(p, to);
        return p.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, err) -> plugin.sync(() -> {
            suspended.remove(p.getUniqueId());
            soon(p, 2);
        }));
    }

    /** Sofortiger Teleport (z.B. beim Verlassen des Servers), funktioniert auch mit Namensschild in andere Welten. */
    public boolean teleportNow(Player p, Location to) {
        prepare(p, to);
        boolean ok = p.teleport(to);
        suspended.remove(p.getUniqueId());
        soon(p, 2);
        return ok;
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(tags.keySet())) removeTag(id);
    }

    // ================================================================ Rang-Partikel
    private void particles() {
        if (!particlesEnabled) return;
        tick++;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getGameMode() == GameMode.SPECTATOR || o.isDead() || o.isInvisible() || o.hasPotionEffect(PotionEffectType.INVISIBILITY)) continue;
            Rank r = rankOf(o);
            if (r.style.equals("none")) continue;
            PlayerData od = plugin.data().getIfLoaded(o.getUniqueId());
            if (od == null || !od.setting("rank-particles", true)) continue;
            List<Pt> pts = points(r, o.getLocation(), tick);
            if (pts.isEmpty()) continue;
            for (Player v : o.getWorld().getPlayers()) {
                if (v.getLocation().distanceSquared(o.getLocation()) > (double) range * range) continue;
                if (!v.equals(o)) {
                    if (!v.canSee(o)) continue;
                    PlayerData vd = plugin.data().getIfLoaded(v.getUniqueId());
                    if (vd != null && !vd.setting("show-particles", true)) continue;
                }
                for (Pt pt : pts) v.spawnParticle(pt.particle, pt.loc, 1, 0, 0, 0, 0, pt.data);
            }
        }
    }

    private static Particle.DustOptions dust(Color c, float size) {
        return new Particle.DustOptions(c, size);
    }

    private List<Pt> points(Rank r, Location base, long t) {
        List<Pt> out = new ArrayList<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        switch (r.style) {
            case "sparkle" -> {
                if (t % 2 == 0) {
                    double a = rnd.nextDouble(Math.PI * 2), rr = 0.35 + rnd.nextDouble(0.25);
                    out.add(new Pt(Particle.DUST, base.clone().add(Math.cos(a) * rr, 0.2 + rnd.nextDouble(1.6), Math.sin(a) * rr),
                            dust(rnd.nextBoolean() ? r.c1 : r.c2, 0.75f)));
                }
            }
            case "ring" -> {
                double y = 0.95 + Math.sin(t * 0.15) * 0.12;
                for (int k = 0; k < 2; k++) {
                    double a = t * 0.38 + k * Math.PI;
                    out.add(new Pt(Particle.DUST, base.clone().add(Math.cos(a) * 0.72, y, Math.sin(a) * 0.72), dust(r.c1, 0.95f)));
                    out.add(new Pt(Particle.DUST, base.clone().add(Math.cos(a - 0.28) * 0.72, y, Math.sin(a - 0.28) * 0.72), dust(r.c2, 0.6f)));
                }
            }
            case "halo" -> {
                for (int k = 0; k < 8; k++) {
                    if (k % 2 != t % 2) continue;
                    double a = t * 0.12 + k * (Math.PI / 4);
                    out.add(new Pt(Particle.DUST, base.clone().add(Math.cos(a) * 0.36, 2.28, Math.sin(a) * 0.36), dust(k % 4 == 0 ? r.c2 : r.c1, 0.6f)));
                }
            }
            case "helix" -> {
                double a = t * 0.45, y = (t % 26) / 26.0 * 2.1;
                out.add(new Pt(Particle.DUST, base.clone().add(Math.cos(a) * 0.58, y, Math.sin(a) * 0.58), dust(r.c1, 0.85f)));
                out.add(new Pt(Particle.DUST, base.clone().add(Math.cos(a + Math.PI) * 0.58, y, Math.sin(a + Math.PI) * 0.58), dust(r.c2, 0.85f)));
                if (t % 13 == 0) out.add(new Pt(Particle.END_ROD, base.clone().add(0, 2.2, 0), null));
            }
            case "crown" -> {
                for (int k = 0; k < 5; k++) {
                    double a = t * 0.1 + k * (Math.PI * 2 / 5);
                    Location l = base.clone().add(Math.cos(a) * 0.33, 2.25, Math.sin(a) * 0.33);
                    if ((t + k) % 3 == 0) out.add(new Pt(Particle.SMALL_FLAME, l, null));
                    else out.add(new Pt(Particle.DUST, l, dust(r.c2, 0.7f)));
                }
                double a = -t * 0.3;
                out.add(new Pt(Particle.DUST, base.clone().add(Math.cos(a) * 0.65, 0.15, Math.sin(a) * 0.65), dust(r.c1, 0.9f)));
                out.add(new Pt(Particle.DUST, base.clone().add(Math.cos(a + Math.PI) * 0.65, 0.15, Math.sin(a + Math.PI) * 0.65), dust(r.c1, 0.9f)));
            }
            default -> { }
        }
        return out;
    }
}
