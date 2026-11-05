package de.minesmp.core.utility;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/** Homes, TPA, Spawn, Back, RTP und Teleport-Wartezeit (Abbruch bei Bewegung/Schaden). */
public final class TeleportService implements Listener {
    private record Pending(BukkitTask task, Location start) {}
    private record TpaRequest(UUID from, boolean here, long time) {}

    private final MineSMP plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, TpaRequest> tpa = new HashMap<>(); // Ziel -> Anfrage
    private final Map<UUID, Location> back = new HashMap<>();

    public TeleportService(MineSMP plugin) {
        this.plugin = plugin;
        plugin.listen(this);
        SimpleCommand.Completer homeComp = (s, a) -> s instanceof Player p && a.length == 1 ? new ArrayList<>(plugin.data().get(p).homes.keySet()) : List.of();
        plugin.command(new SimpleCommand("sethome", "minesmp.home", "Home setzen", this::setHome, (s, a) -> List.of(), "createhome"));
        plugin.command(new SimpleCommand("home", "minesmp.home", "Zum Home teleportieren", this::home, homeComp, "h", "homes"));
        plugin.command(new SimpleCommand("delhome", "minesmp.home", "Home loeschen", this::delHome, homeComp, "deletehome", "rmhome"));
        plugin.command(new SimpleCommand("tpa", "minesmp.tpa", "Teleport-Anfrage", (s, l, a) -> request(s, a, false), "tpask"));
        plugin.command(new SimpleCommand("tpahere", "minesmp.tpa", "Spieler zu dir holen", (s, l, a) -> request(s, a, true)));
        plugin.command(new SimpleCommand("tpaccept", "minesmp.tpa", "Anfrage annehmen", this::accept, "tpyes"));
        plugin.command(new SimpleCommand("tpdeny", "minesmp.tpa", "Anfrage ablehnen", (s, l, a) -> {
            if (s instanceof Player p && tpa.remove(p.getUniqueId()) != null) Msg.send(p, "tpa.denied", "{prefix}&7Anfrage abgelehnt.");
        }, "tpno"));
        plugin.command(new SimpleCommand("back", "minesmp.back", "Zur letzten Position", (s, l, a) -> {
            if (!(s instanceof Player p)) return;
            Location l2 = back.get(p.getUniqueId());
            if (l2 == null) { Msg.send(p, "back.none", "{prefix}&cKeine vorherige Position gespeichert."); return; }
            teleport(p, l2, "letzte Position");
        }, "zurueck"));
        plugin.command(new SimpleCommand("spawn", "minesmp.spawn", "Zum Spawn", (s, l, a) -> {
            Player target = s instanceof Player p ? p : null;
            if (a.length > 0 && s.hasPermission("minesmp.spawn.others")) target = Bukkit.getPlayerExact(a[0]);
            if (target == null) return;
            Location sp = spawn();
            if (target != s) target.teleportAsync(sp);
            else teleport(target, sp, "Spawn");
        }, "lobby", "hub"));
        plugin.command(new SimpleCommand("setspawn", "minesmp.admin.setspawn", "Spawn setzen", (s, l, a) -> {
            if (!(s instanceof Player p)) return;
            plugin.getConfig().set("spawn", PlayerData.locToString(p.getLocation()));
            plugin.saveConfig();
            p.getWorld().setSpawnLocation(p.getLocation());
            plugin.audit().log(p, "SETSPAWN", PlayerData.locToString(p.getLocation()));
            Msg.send(p, "spawn.set", "{prefix}&aSpawn gesetzt.");
        }));
        plugin.command(new SimpleCommand("rtp", "minesmp.rtp", "Zufaelliger Teleport", this::rtp,
                (s, a) -> a.length == 1 ? plugin.getConfig().getStringList("rtp.worlds") : List.of(), "randomtp", "wild"));
    }

    public Location spawn() {
        Location l = PlayerData.locFromString(plugin.getConfig().getString("spawn", ""));
        return l != null ? l : Bukkit.getWorlds().get(0).getSpawnLocation().add(0.5, 0, 0.5);
    }

    /** Teleport mit Wartezeit (Standard 3s), Abbruch bei Bewegung oder Schaden. */
    public void teleport(Player p, Location to, String name) {
        if (to == null) return;
        if (plugin.combat().isTagged(p)) { Msg.send(p, "combat.blocked", "{prefix}&cDas kannst du im Kampf nicht tun."); return; }
        cancel(p);
        int delay = p.hasPermission("minesmp.teleport.instant") ? 0 : plugin.getConfig().getInt("teleport.warmup-seconds", 3);
        if (delay <= 0) { doTeleport(p, to, name); return; }
        Msg.send(p, "teleport.warmup", "{prefix}&7Teleport zu &b{name} &7in &f{s}s&7. Nicht bewegen!", "name", name, "s", String.valueOf(delay));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(p.getUniqueId());
            if (p.isOnline()) doTeleport(p, to, name);
        }, delay * 20L);
        pending.put(p.getUniqueId(), new Pending(task, p.getLocation()));
    }

    private void doTeleport(Player p, Location to, String name) {
        p.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok -> {
            if (ok) {
                Msg.actionbar(p, "teleport.done", "&aTeleportiert: &f{name}", "name", name);
                p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
            }
        });
    }

    private void cancel(Player p) {
        Pending pd = pending.remove(p.getUniqueId());
        if (pd != null) pd.task.cancel();
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Pending pd = pending.get(e.getPlayer().getUniqueId());
        if (pd == null || !e.hasChangedBlock()) return;
        if (pd.start.getWorld() != e.getTo().getWorld() || pd.start.distanceSquared(e.getTo()) > 0.6) {
            cancel(e.getPlayer());
            Msg.send(e.getPlayer(), "teleport.cancelled", "{prefix}&cTeleport abgebrochen - du hast dich bewegt.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && pending.containsKey(p.getUniqueId())) {
            cancel(p);
            Msg.send(p, "teleport.cancelled-damage", "{prefix}&cTeleport abgebrochen - du hast Schaden erhalten.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN || e.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND) {
            if (!plugin.duels().inDuel(e.getPlayer())) back.put(e.getPlayer().getUniqueId(), e.getFrom());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (e.getEntity().hasPermission("minesmp.back.death") && !plugin.duels().inDuel(e.getEntity())) back.put(e.getEntity().getUniqueId(), e.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        if (!e.isBedSpawn() && !e.isAnchorSpawn()) e.setRespawnLocation(spawn());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!e.getPlayer().hasPlayedBefore() || plugin.getConfig().getBoolean("teleport.spawn-on-join", false)) {
            e.getPlayer().teleportAsync(spawn());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancel(e.getPlayer());
        tpa.remove(e.getPlayer().getUniqueId());
    }

    // ---------------- Homes ----------------

    public int maxHomes(Player p) {
        int max = plugin.getConfig().getInt("homes.default-max", 3);
        for (int i = 100; i > max; i--) if (p.hasPermission("minesmp.homes." + i)) return i;
        return max;
    }

    private void setHome(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        String name = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "home";
        if (!name.matches("[a-z0-9_]{1,16}")) { Msg.send(p, "home.bad-name", "{prefix}&cName: 1-16 Zeichen (a-z, 0-9, _)."); return; }
        if (!plugin.worlds().homesAllowed(p.getWorld())) { Msg.send(p, "home.world", "{prefix}&cIn dieser Welt kannst du keine Homes setzen."); return; }
        PlayerData d = plugin.data().get(p);
        if (!d.homes.containsKey(name) && d.homes.size() >= maxHomes(p)) {
            Msg.send(p, "home.limit", "{prefix}&cDu kannst maximal &f{max} &cHomes setzen.", "max", String.valueOf(maxHomes(p)));
            return;
        }
        d.homes.put(name, PlayerData.locToString(p.getLocation()));
        d.dirty = true;
        Msg.send(p, "home.set", "{prefix}&aHome &f{name} &agesetzt. &7({n}/{max})", "name", name, "n", String.valueOf(d.homes.size()), "max", String.valueOf(maxHomes(p)));
    }

    private void home(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        PlayerData d = plugin.data().get(p);
        if (d.homes.isEmpty()) { Msg.send(p, "home.none", "{prefix}&cDu hast keine Homes. &7/sethome [Name]"); return; }
        if (a.length == 0 && d.homes.size() > 1) { openHomes(p); return; }
        String name = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : d.homes.keySet().iterator().next();
        String loc = d.homes.get(name);
        if (loc == null) { Msg.send(p, "home.unknown", "{prefix}&cHome &f{name} &cexistiert nicht.", "name", name); return; }
        Location to = PlayerData.locFromString(loc);
        if (to == null) { Msg.send(p, "home.world-missing", "{prefix}&cDie Welt dieses Homes existiert nicht mehr."); return; }
        teleport(p, to, "Home " + name);
    }

    private void openHomes(Player p) {
        PlayerData d = plugin.data().get(p);
        Menu m = new Menu(Math.max(1, (d.homes.size() + 8) / 9), "&8Deine Homes") {};
        int i = 0;
        for (var e : d.homes.entrySet()) {
            String[] parts = e.getValue().split(";");
            m.set(i++, Items.of(Material.RED_BED, 1, "&b" + e.getKey(), List.of("&7Welt: &f" + parts[0],
                    "&7Position: &f" + (int) Double.parseDouble(parts[1]) + ", " + (int) Double.parseDouble(parts[2]) + ", " + (int) Double.parseDouble(parts[3]),
                    "", "&bLinksklick &7» Teleport", "&cShift+Rechtsklick &7» Loeschen")), ev -> {
                if (ev.isShiftClick() && ev.isRightClick()) {
                    d.homes.remove(e.getKey());
                    d.dirty = true;
                    Msg.send(p, "home.deleted", "{prefix}&7Home &f{name} &7geloescht.", "name", e.getKey());
                    Menu.close(p);
                } else {
                    Menu.close(p);
                    teleport(p, PlayerData.locFromString(e.getValue()), "Home " + e.getKey());
                }
            });
        }
        m.open(p);
    }

    private void delHome(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p) || a.length == 0) return;
        PlayerData d = plugin.data().get(p);
        if (d.homes.remove(a[0].toLowerCase(Locale.ROOT)) == null) { Msg.send(p, "home.unknown", "{prefix}&cHome &f{name} &cexistiert nicht.", "name", a[0]); return; }
        d.dirty = true;
        Msg.send(p, "home.deleted", "{prefix}&7Home &f{name} &7geloescht.", "name", a[0]);
    }

    // ---------------- TPA ----------------

    private void request(CommandSender s, String[] a, boolean here) {
        if (!(s instanceof Player p)) return;
        if (a.length == 0) { Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", here ? "/tpahere <Spieler>" : "/tpa <Spieler>"); return; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null || t.equals(p) || !p.canSee(t)) { Msg.send(p, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a[0]); return; }
        PlayerData td = plugin.data().get(t);
        if (!td.setting("tpa", true) || td.ignored.contains(p.getUniqueId().toString())) {
            Msg.send(p, "tpa.disabled", "{prefix}&f{name} &cnimmt keine Teleport-Anfragen an.", "name", t.getName());
            return;
        }
        long cd = plugin.cooldowns().remaining(p.getUniqueId(), "tpa");
        if (cd > 0) { Msg.send(p, "cooldown", "{prefix}&cBitte warte noch &f{time}&c.", "time", TimeUtil.format(cd)); return; }
        plugin.cooldowns().set(p.getUniqueId(), "tpa", plugin.getConfig().getLong("cooldowns.tpa", 15) * 1000);
        tpa.put(t.getUniqueId(), new TpaRequest(p.getUniqueId(), here, System.currentTimeMillis()));
        Msg.send(p, "tpa.sent", "{prefix}&7Anfrage an &f{name} &7gesendet.", "name", t.getName());
        t.sendMessage(Text.c(Msg.get(here ? "tpa.received-here" : "tpa.received",
                        here ? "{prefix}&f{name} &7moechte, dass du dich zu ihm/ihr teleportierst. " : "{prefix}&f{name} &7moechte sich zu dir teleportieren. ", "name", p.getName()))
                .append(Text.c("&a&l[ANNEHMEN]").clickEvent(ClickEvent.runCommand("/tpaccept")))
                .append(Text.c(" &c&l[ABLEHNEN]").clickEvent(ClickEvent.runCommand("/tpdeny"))));
    }

    private void accept(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        TpaRequest r = tpa.remove(p.getUniqueId());
        Player from = r == null ? null : Bukkit.getPlayer(r.from);
        if (from == null || System.currentTimeMillis() - r.time > plugin.getConfig().getLong("teleport.tpa-timeout-seconds", 60) * 1000) {
            Msg.send(p, "tpa.none", "{prefix}&cKeine offene Teleport-Anfrage.");
            return;
        }
        Msg.send(p, "tpa.accepted", "{prefix}&aAnfrage angenommen.");
        Msg.send(from, "tpa.accepted-other", "{prefix}&f{name} &ahat deine Anfrage angenommen.", "name", p.getName());
        if (r.here) teleport(p, from.getLocation(), from.getName());
        else teleport(from, p.getLocation(), p.getName());
    }

    // ---------------- RTP ----------------

    private void rtp(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        List<String> worlds = plugin.getConfig().getStringList("rtp.worlds");
        String wn = a.length > 0 ? a[0] : plugin.getConfig().getString("rtp.default-world", "world");
        if (!worlds.contains(wn)) { Msg.send(p, "rtp.bad-world", "{prefix}&cRTP ist nur in diesen Welten moeglich: &f{list}", "list", String.join(", ", worlds)); return; }
        World w = Bukkit.getWorld(wn);
        if (w == null) { Msg.send(p, "rtp.bad-world", "{prefix}&cRTP ist nur in diesen Welten moeglich: &f{list}", "list", String.join(", ", worlds)); return; }
        long cd = plugin.cooldowns().remaining(p.getUniqueId(), "rtp");
        if (cd > 0 && !p.hasPermission("minesmp.rtp.nocooldown")) { Msg.send(p, "cooldown", "{prefix}&cBitte warte noch &f{time}&c.", "time", TimeUtil.format(cd)); return; }
        if (plugin.combat().isTagged(p)) { Msg.send(p, "combat.blocked", "{prefix}&cDas kannst du im Kampf nicht tun."); return; }
        plugin.cooldowns().set(p.getUniqueId(), "rtp", plugin.getConfig().getLong("rtp.cooldown-seconds", 300) * 1000);
        Msg.send(p, "rtp.searching", "{prefix}&7Suche einen sicheren Ort...");
        findSafe(w, 0).thenAccept(loc -> plugin.sync(() -> {
            if (!p.isOnline()) return;
            if (loc == null) {
                plugin.cooldowns().set(p.getUniqueId(), "rtp", 1);
                Msg.send(p, "rtp.failed", "{prefix}&cKein sicherer Ort gefunden. Versuche es erneut.");
                return;
            }
            teleport(p, loc, "Zufallsort (" + loc.getBlockX() + ", " + loc.getBlockZ() + ")");
        }));
    }

    private static final java.util.Set<Material> UNSAFE = java.util.EnumSet.of(Material.LAVA, Material.WATER, Material.MAGMA_BLOCK, Material.CACTUS,
            Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH, Material.POINTED_DRIPSTONE);

    /** Laedt Chunks asynchron und sucht einen sicheren Block (max. 10 Versuche). */
    public CompletableFuture<Location> findSafe(World w, int attempt) {
        if (attempt >= 10) return CompletableFuture.completedFuture(null);
        int min = plugin.getConfig().getInt("rtp.min-radius", 500), max = plugin.getConfig().getInt("rtp.max-radius", 5000);
        if (w.getWorldBorder().getSize() / 2 - 16 < max) max = (int) Math.max(min + 1, w.getWorldBorder().getSize() / 2 - 16);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int dist = r.nextInt(min, Math.max(min + 1, max));
        double ang = r.nextDouble(Math.PI * 2);
        Location c = w.getWorldBorder().getCenter();
        int x = (int) (c.getX() + Math.cos(ang) * dist), z = (int) (c.getZ() + Math.sin(ang) * dist);
        return w.getChunkAtAsync(x >> 4, z >> 4).thenCompose(chunk -> {
            Block top;
            if (w.getEnvironment() == World.Environment.NETHER) {
                top = null;
                for (int y = 100; y > 32; y--) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType().isSolid() && b.getRelative(0, 1, 0).isEmpty() && b.getRelative(0, 2, 0).isEmpty()) { top = b; break; }
                }
            } else top = w.getHighestBlockAt(x, z);
            if (top == null || !top.getType().isSolid() || UNSAFE.contains(top.getType()) || top.getType().name().endsWith("LEAVES")
                    || top.getY() < w.getMinHeight() + 5) {
                return findSafe(w, attempt + 1);
            }
            return CompletableFuture.completedFuture(top.getLocation().add(0.5, 1, 0.5));
        });
    }
}
