package de.minesmp.core.world;

import de.minesmp.core.MineSMP;
import de.minesmp.core.clan.Clan;
import de.minesmp.core.gui.ConfirmMenu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.utility.RewardService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.InventoryHolder;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Grundstuecke (/claim): Spieler sichern Chunks. Fremde koennen dort nicht bauen, abbauen,
 * Kisten oeffnen, Tiere verletzen usw. Vertraute Spieler (/claim trust) und auf Wunsch Clan-Mitglieder duerfen alles.
 * Raid-Zeit (config claims.raid): In festen Zeitfenstern sind TNT-Explosionen und Kisten in Grundstuecken erlaubt.
 */
public final class ClaimService implements Listener {
    public record Claim(String world, int cx, int cz, UUID owner, String ownerName) {}

    private final MineSMP plugin;
    private final Map<String, Claim> claims = new HashMap<>();
    private final Map<UUID, Set<UUID>> trust = new HashMap<>();
    private final Map<UUID, Integer> counts = new HashMap<>();
    private final Set<UUID> bypass = new HashSet<>();
    private boolean raidWasActive;

    public ClaimService(MineSMP plugin) {
        this.plugin = plugin;
        plugin.db().run(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS claims (world VARCHAR(64) NOT NULL, cx INTEGER NOT NULL, cz INTEGER NOT NULL, owner VARCHAR(36) NOT NULL,"
                        + " owner_name VARCHAR(16), created BIGINT NOT NULL, PRIMARY KEY (world, cx, cz))");
                st.execute("CREATE TABLE IF NOT EXISTS claim_trust (owner VARCHAR(36) NOT NULL, trusted VARCHAR(36) NOT NULL, PRIMARY KEY (owner, trusted))");
            }
        });
        plugin.db().queryThen(c -> {
            List<Claim> out = new ArrayList<>();
            Map<UUID, Set<UUID>> tr = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT world, cx, cz, owner, owner_name FROM claims"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Claim(rs.getString(1), rs.getInt(2), rs.getInt(3), UUID.fromString(rs.getString(4)), rs.getString(5)));
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT owner, trusted FROM claim_trust"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tr.computeIfAbsent(UUID.fromString(rs.getString(1)), k -> new HashSet<>()).add(UUID.fromString(rs.getString(2)));
            }
            return Map.entry(out, tr);
        }, res -> {
            for (Claim cl : res.getKey()) { claims.put(key(cl.world, cl.cx, cl.cz), cl); counts.merge(cl.owner, 1, Integer::sum); }
            trust.putAll(res.getValue());
            if (!claims.isEmpty()) plugin.getLogger().info(claims.size() + " Grundstuecks-Chunks geladen.");
        });
        plugin.listen(this);
        plugin.command(new SimpleCommand("claim", "minesmp.claim", "Grundstuecke", this::cmd, (s, a) -> {
            if (a.length == 1) {
                List<String> l = new ArrayList<>(List.of("info", "unclaim", "unclaimall", "trust", "untrust", "list", "show", "raid"));
                if (s.hasPermission("minesmp.admin.claims")) { l.add("bypass"); l.add("adminremove"); }
                return l;
            }
            if (a.length == 2 && (a[0].equalsIgnoreCase("trust") || a[0].equalsIgnoreCase("untrust"))) return null;
            return List.of();
        }, "gs", "grundstueck"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::raidCheck, 20 * 5, 20 * 5);
    }

    private static String key(String world, int cx, int cz) { return world + ";" + cx + ";" + cz; }

    public Claim at(Location l) {
        if (l == null || l.getWorld() == null) return null;
        return claims.get(key(l.getWorld().getName(), l.getBlockX() >> 4, l.getBlockZ() >> 4));
    }

    public Claim at(Block b) {
        return claims.get(key(b.getWorld().getName(), b.getX() >> 4, b.getZ() >> 4));
    }

    public boolean isTrusted(UUID owner, UUID who) {
        if (owner.equals(who)) return true;
        Set<UUID> t = trust.get(owner);
        if (t != null && t.contains(who)) return true;
        if (plugin.getConfig().getBoolean("claims.clan-trust", true)) {
            Clan a = plugin.clans().of(owner), b = plugin.clans().of(who);
            return a != null && a == b;
        }
        return false;
    }

    /** Darf der Spieler an dieser Stelle bauen/interagieren? */
    public boolean canUse(Player p, Location l) {
        Claim c = at(l);
        return c == null || bypass.contains(p.getUniqueId()) || isTrusted(c.owner, p.getUniqueId());
    }

    public int count(UUID owner) { return counts.getOrDefault(owner, 0); }

    public int max(Player p) {
        int max = plugin.getConfig().getInt("claims.default-max", 9);
        for (int i = 500; i > max; i--) if (p.hasPermission("minesmp.claims." + i)) return i;
        return max;
    }

    // ---------------- Raid-Zeit ----------------

    public boolean raidActive() {
        if (!plugin.getConfig().getBoolean("claims.raid.enabled", true)) return false;
        LocalDateTime now = LocalDateTime.now(RewardService.ZONE);
        for (String w : plugin.getConfig().getStringList("claims.raid.windows")) {
            Window win = Window.parse(w);
            if (win != null && win.contains(now)) return true;
        }
        return false;
    }

    /** Text fuer %raid%: "AKTIV" oder naechster Start (z.B. "Sa 19:00"). */
    public String raidText() {
        if (!plugin.getConfig().getBoolean("claims.raid.enabled", true)) return "-";
        if (raidActive()) return "&c&lAKTIV";
        LocalDateTime now = LocalDateTime.now(RewardService.ZONE);
        LocalDateTime best = null;
        for (String w : plugin.getConfig().getStringList("claims.raid.windows")) {
            Window win = Window.parse(w);
            if (win == null) continue;
            LocalDateTime n = win.nextStart(now);
            if (best == null || n.isBefore(best)) best = n;
        }
        if (best == null) return "-";
        String[] days = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};
        return days[best.getDayOfWeek().getValue() - 1] + " " + String.format("%02d:%02d", best.getHour(), best.getMinute());
    }

    private record Window(DayOfWeek day, LocalTime from, LocalTime to) {
        static Window parse(String s) {
            try {
                String[] p = s.trim().split("\\s+");
                DayOfWeek d = p[0].equalsIgnoreCase("DAILY") ? null : DayOfWeek.valueOf(p[0].toUpperCase(Locale.ROOT));
                String[] t = p[1].split("-");
                return new Window(d, LocalTime.parse(t[0]), LocalTime.parse(t[1]));
            } catch (Exception e) {
                return null;
            }
        }
        boolean contains(LocalDateTime now) {
            if (day != null && now.getDayOfWeek() != day) return false;
            LocalTime t = now.toLocalTime();
            return !t.isBefore(from) && t.isBefore(to);
        }
        LocalDateTime nextStart(LocalDateTime now) {
            for (int i = 0; i <= 7; i++) {
                LocalDateTime c = now.toLocalDate().plusDays(i).atTime(from);
                if ((day == null || c.getDayOfWeek() == day) && c.isAfter(now)) return c;
            }
            return now.plusDays(8);
        }
    }

    private void raidCheck() {
        boolean now = raidActive();
        if (now == raidWasActive) return;
        raidWasActive = now;
        if (now) Msg.broadcast("claims.raid-start", "{prefix}&c&lRAID-ZEIT! &7In Grundstuecken sind jetzt TNT und fremde Kisten erlaubt. Schuetzt eure Basen!");
        else Msg.broadcast("claims.raid-end", "{prefix}&a&lRaid-Zeit vorbei. &7Alle Grundstuecke sind wieder geschuetzt.");
    }

    // ---------------- Befehle ----------------

    private void cmd(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        String sub = a.length == 0 ? "claim" : a[0].toLowerCase(Locale.ROOT);
        Chunk ch = p.getLocation().getChunk();
        Claim here = at(p.getLocation());
        switch (sub) {
            case "claim" -> claim(p, ch);
            case "info" -> {
                if (here == null) Msg.send(p, "claims.wild", "{prefix}&7Hier ist &fWildnis&7. Sichern mit &b/claim&7.");
                else Msg.send(p, "claims.info", "{prefix}&7Grundstueck von &b{name} &8(&7Chunk {x}, {z}&8)", "name", here.ownerName, "x", String.valueOf(here.cx), "z", String.valueOf(here.cz));
                Msg.send(p, "claims.count", "&7Deine Chunks: &f{n}&7/&f{max} &8| &7Raid-Zeit: {raid}", "n", String.valueOf(count(p.getUniqueId())), "max", String.valueOf(max(p)), "raid", raidText());
            }
            case "unclaim" -> {
                if (here == null || (!here.owner.equals(p.getUniqueId()) && !bypass.contains(p.getUniqueId()))) { Msg.send(p, "claims.notyours", "{prefix}&cDas ist nicht dein Grundstueck."); return; }
                remove(here);
                Msg.send(p, "claims.removed", "{prefix}&7Chunk freigegeben.");
            }
            case "unclaimall" -> ConfirmMenu.open(p, "&8Alle Chunks freigeben?", Items.of(Material.BARRIER, "&cAlle Grundstuecke freigeben"), List.of("&7Das kann nicht rueckgaengig gemacht werden."), () -> {
                int n = 0;
                for (Claim c : new ArrayList<>(claims.values())) if (c.owner.equals(p.getUniqueId())) { remove(c); n++; }
                Msg.send(p, "claims.removed-all", "{prefix}&7{n} Chunks freigegeben.", "n", String.valueOf(n));
            });
            case "trust", "untrust" -> {
                if (a.length < 2) { Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/claim " + sub + " <Spieler>"); return; }
                OfflinePlayer t = Bukkit.getPlayerExact(a[1]);
                if (t == null) t = Bukkit.getOfflinePlayerIfCached(a[1]);
                if (t == null || (!t.isOnline() && !t.hasPlayedBefore()) || t.getUniqueId().equals(p.getUniqueId())) { Msg.send(p, "player-unknown", "{prefix}&cSpieler &f{name} &cwar noch nie auf MineSMP.", "name", a[1]); return; }
                UUID tid = t.getUniqueId();
                if (sub.equals("trust")) {
                    trust.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(tid);
                    plugin.db().update("REPLACE INTO claim_trust (owner, trusted) VALUES (?, ?)", p.getUniqueId().toString(), tid.toString());
                    Msg.send(p, "claims.trusted", "{prefix}&a{name} darf jetzt auf all deinen Grundstuecken bauen.", "name", a[1]);
                } else {
                    Set<UUID> set = trust.get(p.getUniqueId());
                    if (set != null) set.remove(tid);
                    plugin.db().update("DELETE FROM claim_trust WHERE owner = ? AND trusted = ?", p.getUniqueId().toString(), tid.toString());
                    Msg.send(p, "claims.untrusted", "{prefix}&7{name} hat keine Rechte mehr.", "name", a[1]);
                }
            }
            case "list" -> {
                List<String> parts = new ArrayList<>();
                for (Claim c : claims.values()) if (c.owner.equals(p.getUniqueId())) parts.add(c.world + " " + (c.cx * 16 + 8) + "/" + (c.cz * 16 + 8));
                Msg.send(p, "claims.list", "{prefix}&7Deine Chunks ({n}): &f{list}", "n", String.valueOf(parts.size()), "list", parts.isEmpty() ? "-" : String.join("&7, &f", parts));
                Set<UUID> t = trust.getOrDefault(p.getUniqueId(), Set.of());
                List<String> names = new ArrayList<>();
                for (UUID id : t) { OfflinePlayer op = Bukkit.getOfflinePlayer(id); names.add(op.getName() == null ? id.toString().substring(0, 8) : op.getName()); }
                Msg.send(p, "claims.trustlist", "&7Vertraut: &f{list}", "list", names.isEmpty() ? "-" : String.join(", ", names));
            }
            case "show" -> show(p);
            case "raid" -> Msg.send(p, "claims.raid-info", "{prefix}&7Raid-Zeit: {raid} &8| &7Zeitfenster: &f{windows}", "raid", raidText(),
                    "windows", String.join(", ", plugin.getConfig().getStringList("claims.raid.windows")));
            case "bypass" -> {
                if (!p.hasPermission("minesmp.admin.claims")) return;
                if (!bypass.remove(p.getUniqueId())) bypass.add(p.getUniqueId());
                Msg.send(p, "claims.bypass", "{prefix}&7Grundstuecks-Bypass: {state}", "state", bypass.contains(p.getUniqueId()) ? "&aan" : "&caus");
            }
            case "adminremove" -> {
                if (!p.hasPermission("minesmp.admin.claims") || here == null) return;
                remove(here);
                plugin.audit().log(p, "CLAIM_REMOVE", here.ownerName + " " + here.world + " " + here.cx + "/" + here.cz);
                Msg.send(p, "claims.removed", "{prefix}&7Chunk freigegeben.");
            }
            default -> Msg.send(p, "claims.help", "{prefix}&b/claim &7sichert den Chunk &8| &b/claim info, unclaim, trust, untrust, list, show, raid");
        }
    }

    private void claim(Player p, Chunk ch) {
        World w = ch.getWorld();
        if (!plugin.getConfig().getStringList("claims.worlds").contains(w.getName())) { Msg.send(p, "claims.world", "{prefix}&cIn dieser Welt kannst du keine Grundstuecke sichern."); return; }
        Claim c = claims.get(key(w.getName(), ch.getX(), ch.getZ()));
        if (c != null) { Msg.send(p, "claims.taken", "{prefix}&cDieser Chunk gehoert schon &f{name}&c.", "name", c.ownerName); return; }
        Location sp = w.getSpawnLocation();
        int minDist = plugin.getConfig().getInt("claims.min-distance-from-spawn", 150);
        double dx = ch.getX() * 16 + 8 - sp.getX(), dz = ch.getZ() * 16 + 8 - sp.getZ();
        if (w.getEnvironment() == World.Environment.NORMAL && Math.sqrt(dx * dx + dz * dz) < minDist) {
            Msg.send(p, "claims.spawn", "{prefix}&cZu nah am Weltspawn (mind. {n} Bloecke).", "n", String.valueOf(minDist));
            return;
        }
        if (count(p.getUniqueId()) >= max(p)) { Msg.send(p, "claims.limit", "{prefix}&cDu hast schon {max} Chunks. Mehr mit Raengen!", "max", String.valueOf(max(p))); return; }
        double cost = count(p.getUniqueId()) < plugin.getConfig().getInt("claims.free-chunks", 1) ? 0 : plugin.getConfig().getDouble("claims.cost-per-chunk", 2500);
        if (cost > 0 && !plugin.eco().withdraw(p, cost, "CLAIM", w.getName() + " " + ch.getX() + "/" + ch.getZ())) {
            Msg.send(p, "claims.money", "{prefix}&cEin Chunk kostet &f{cost}&c.", "cost", plugin.eco().format(cost));
            return;
        }
        Claim nc = new Claim(w.getName(), ch.getX(), ch.getZ(), p.getUniqueId(), p.getName());
        claims.put(key(nc.world, nc.cx, nc.cz), nc);
        counts.merge(p.getUniqueId(), 1, Integer::sum);
        plugin.db().update("INSERT INTO claims (world, cx, cz, owner, owner_name, created) VALUES (?, ?, ?, ?, ?, ?)",
                nc.world, nc.cx, nc.cz, nc.owner.toString(), nc.ownerName, System.currentTimeMillis());
        Msg.send(p, "claims.claimed", "{prefix}&aChunk gesichert! &7({n}/{max}{cost}) &8- &7Raid-Zeit: {raid}", "n", String.valueOf(count(p.getUniqueId())),
                "max", String.valueOf(max(p)), "cost", cost > 0 ? ", " + plugin.eco().format(cost) : ", kostenlos", "raid", raidText());
        show(p);
    }

    private void remove(Claim c) {
        claims.remove(key(c.world, c.cx, c.cz));
        counts.merge(c.owner, -1, Integer::sum);
        plugin.db().update("DELETE FROM claims WHERE world = ? AND cx = ? AND cz = ?", c.world, c.cx, c.cz);
    }

    private void show(Player p) {
        Chunk ch = p.getLocation().getChunk();
        int y = p.getLocation().getBlockY();
        new org.bukkit.scheduler.BukkitRunnable() {
            int runs = 0;
            @Override public void run() {
                if (!p.isOnline() || runs++ > 20) { cancel(); return; }
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    Claim c = claims.get(key(ch.getWorld().getName(), ch.getX() + dx, ch.getZ() + dz));
                    if (c == null) continue;
                    Particle.DustOptions col = new Particle.DustOptions(c.owner.equals(p.getUniqueId()) ? org.bukkit.Color.fromRGB(0x55FFFF) : org.bukkit.Color.fromRGB(0xFF5555), 1.4f);
                    int bx = (ch.getX() + dx) * 16, bz = (ch.getZ() + dz) * 16;
                    for (int i = 0; i <= 16; i += 2) {
                        for (int h = 0; h <= 2; h++) {
                            p.spawnParticle(Particle.DUST, bx + i, y + h, bz, 1, col);
                            p.spawnParticle(Particle.DUST, bx + i, y + h, bz + 16, 1, col);
                            p.spawnParticle(Particle.DUST, bx, y + h, bz + i, 1, col);
                            p.spawnParticle(Particle.DUST, bx + 16, y + h, bz + i, 1, col);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0, 10);
    }

    // ---------------- Schutz ----------------

    private boolean deny(Player p, Location l) {
        if (canUse(p, l)) return false;
        Claim c = at(l);
        if (plugin.cooldowns().tryUse(p.getUniqueId(), "claim-msg", 1500))
            Msg.actionbar(p, "claims.denied", "&cGrundstueck von &f{name} &8- &7kein Zugriff", "name", c == null ? "?" : c.ownerName);
        return true;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        if (e.getAction() == Action.PHYSICAL) {
            if (b.getType() == Material.FARMLAND || Tag.PRESSURE_PLATES.isTagged(b.getType()) || b.getType() == Material.TRIPWIRE) {
                if (!canUse(e.getPlayer(), b.getLocation())) e.setCancelled(true);
            }
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Claim c = at(b);
        if (c == null || canUse(e.getPlayer(), b.getLocation())) return;
        BlockState st = b.getState(false);
        boolean container = st instanceof InventoryHolder || st instanceof Container;
        if (container && raidActive() && plugin.getConfig().getBoolean("claims.raid.allow-containers", true)) return;
        Material m = b.getType();
        boolean interactive = container || m.isInteractable() || Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m) || Tag.BUTTONS.isTagged(m);
        // Platzieren auf fremdem Grund wird ueber BlockPlaceEvent geregelt; hier nur Benutzen
        if (interactive || (e.getItem() != null && (e.getItem().getType().name().endsWith("_SPAWN_EGG") || e.getItem().getType() == Material.BONE_MEAL
                || e.getItem().getType() == Material.FLINT_AND_STEEL || e.getItem().getType() == Material.FIRE_CHARGE || e.getItem().getType() == Material.ARMOR_STAND
                || e.getItem().getType().name().endsWith("_BOAT") || e.getItem().getType().name().endsWith("MINECART")))) {
            if (b.getType() == Material.ENDER_CHEST || b.getType() == Material.CRAFTING_TABLE) return;
            if (deny(e.getPlayer(), b.getLocation())) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity en = e.getRightClicked();
        if (en instanceof Player || en instanceof Monster) return;
        if (en.getType() == EntityType.VILLAGER || en.getType() == EntityType.WANDERING_TRADER) return;
        if (deny(e.getPlayer(), en.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageEntity(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        if (victim instanceof Player || victim instanceof Monster) return;
        Player attacker = e.getDamager() instanceof Player p ? p : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p2 ? p2 : null;
        if (attacker != null && deny(attacker, victim.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHanging(HangingBreakByEntityEvent e) {
        Player p = e.getRemover() instanceof Player pl ? pl : e.getRemover() instanceof Projectile pr && pr.getShooter() instanceof Player p2 ? p2 : null;
        if (p != null) { if (deny(p, e.getEntity().getLocation())) e.setCancelled(true); }
        else if (at(e.getEntity().getLocation()) != null) e.setCancelled(true);
    }

    private boolean raidExplosion(Entity source) {
        if (!raidActive() || !plugin.getConfig().getBoolean("claims.raid.allow-explosions", true)) return false;
        return source != null && (source.getType() == EntityType.TNT || source.getType() == EntityType.TNT_MINECART);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (raidExplosion(e.getEntity())) return;
        e.blockList().removeIf(b -> at(b) != null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> at(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if ((e.getBlock().getX() >> 4) == (e.getToBlock().getX() >> 4) && (e.getBlock().getZ() >> 4) == (e.getToBlock().getZ() >> 4)) return;
        Claim to = at(e.getToBlock());
        if (to == null) return;
        Claim from = at(e.getBlock());
        if (from == null || !from.owner.equals(to.owner)) e.setCancelled(true);
    }

    private boolean crosses(Block piston, List<Block> blocks, org.bukkit.block.BlockFace dir) {
        Claim pc = at(piston);
        for (Block b : blocks) {
            for (Block t : new Block[]{b, b.getRelative(dir)}) {
                Claim c = at(t);
                if (c != null && (pc == null || !pc.owner.equals(c.owner))) return true;
            }
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) {
        if (crosses(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (crosses(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (at(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (e.getSource().getType() == Material.FIRE && at(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (at(e.getBlock()) == null) return;
        if (e.getPlayer() != null) { if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); return; }
        if (e.getCause() == BlockIgniteEvent.IgniteCause.SPREAD || e.getCause() == BlockIgniteEvent.IgniteCause.LAVA) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (at(e.getBlock()) == null) return;
        if (e.getEntity() instanceof Player p) { if (deny(p, e.getBlock().getLocation())) e.setCancelled(true); return; }
        EntityType t = e.getEntityType();
        if (t == EntityType.ENDERMAN || t == EntityType.WITHER || t == EntityType.RAVAGER || t == EntityType.SILVERFISH) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom(), t = e.getTo();
        if ((f.getBlockX() >> 4) == (t.getBlockX() >> 4) && (f.getBlockZ() >> 4) == (t.getBlockZ() >> 4) && f.getWorld() == t.getWorld()) return;
        Claim a = at(f), b = at(t);
        UUID ao = a == null ? null : a.owner, bo = b == null ? null : b.owner;
        if (java.util.Objects.equals(ao, bo)) return;
        Player p = e.getPlayer();
        if (b == null) Msg.actionbar(p, "claims.enter-wild", "&7Du betrittst die &fWildnis");
        else Msg.actionbar(p, "claims.enter", "&7Grundstueck von &b{name}{raid}", "name", b.ownerName, "raid", raidActive() ? " &8| &c&lRAID-ZEIT" : "");
    }

    public String claimsText(Player p) {
        return count(p.getUniqueId()) + "/" + max(p);
    }
}
