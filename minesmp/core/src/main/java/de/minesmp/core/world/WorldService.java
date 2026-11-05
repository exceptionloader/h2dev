package de.minesmp.core.world;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.utility.RewardService;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Weltverwaltung:
 * - Spawn-Welt (leere Void-Welt, wird beim ersten Start mit Plattform erzeugt)
 * - Farmwelt (Ressourcenwelt), automatischer Reset nach Zeitplan und /farmwelt reset
 * - Gamerules (Schlafsystem: playersSleepingPercentage), Spawn-Partikel, Join-/Quit-Nachrichten
 */
public final class WorldService implements Listener {
    private final MineSMP plugin;
    private boolean resetting;

    public WorldService(MineSMP plugin) {
        this.plugin = plugin;
        plugin.listen(this);
        Bukkit.getScheduler().runTask(plugin, this::setupWorlds);
        plugin.command(new SimpleCommand("farmwelt", "minesmp.farmwelt", "Zur Farmwelt", (s, l, a) -> {
            if (a.length > 0 && a[0].equalsIgnoreCase("reset") && s.hasPermission("minesmp.admin.farmwelt")) {
                plugin.audit().log(s, "FARMWORLD_RESET", "manuell");
                resetFarmworld();
                return;
            }
            if (!(s instanceof Player p)) return;
            World w = Bukkit.getWorld(farmName());
            if (w == null || resetting) { Msg.send(p, "farmwelt.unavailable", "{prefix}&cDie Farmwelt ist gerade nicht verfuegbar."); return; }
            Msg.send(p, "rtp.searching", "{prefix}&7Suche einen sicheren Ort...");
            plugin.teleport().findSafe(w, 0).thenAccept(loc -> plugin.sync(() -> {
                if (loc != null && p.isOnline()) plugin.teleport().teleport(p, loc, "Farmwelt");
            }));
        }, (s, a) -> a.length == 1 && s.hasPermission("minesmp.admin.farmwelt") ? List.of("reset") : List.of(), "farmworld", "resource", "fw"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkReset, 20 * 60, 20 * 60);
        Bukkit.getScheduler().runTaskTimer(plugin, this::spawnParticles, 100, 10);
    }

    public String spawnWorldName() { return plugin.getConfig().getString("worlds.spawn", "spawn"); }
    private String farmName() { return plugin.getConfig().getString("worlds.farmworld.name", "farmwelt"); }

    public boolean isSurvival(World w) {
        return plugin.getConfig().getStringList("worlds.survival").contains(w.getName());
    }

    public boolean homesAllowed(World w) {
        return !plugin.getConfig().getStringList("homes.blocked-worlds").contains(w.getName());
    }

    private void setupWorlds() {
        String spawnName = spawnWorldName();
        if (plugin.getConfig().getBoolean("worlds.create-spawn-world", true) && Bukkit.getWorld(spawnName) == null) {
            World w = new WorldCreator(spawnName).generator(new VoidGenerator()).generateStructures(false).createWorld();
            if (w != null) {
                w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
                w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
                w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                w.setGameRule(GameRule.DO_FIRE_TICK, false);
                w.setGameRule(GameRule.MOB_GRIEFING, false);
                w.setGameRule(GameRule.FALL_DAMAGE, false);
                w.setTime(6000);
                if (w.getBlockAt(0, 99, 0).getType() == Material.AIR) {
                    for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++)
                        w.getBlockAt(x, 99, z).setType((x + z) % 2 == 0 ? Material.LIGHT_BLUE_CONCRETE : Material.WHITE_CONCRETE);
                }
                w.setSpawnLocation(0, 100, 0);
                if (plugin.getConfig().getString("spawn", "").isEmpty()) {
                    plugin.getConfig().set("spawn", spawnName + ";0.5;100;0.5;0;0");
                    plugin.saveConfig();
                }
                plugin.getLogger().info("Spawn-Welt '" + spawnName + "' erstellt. Baue den Spawn und nutze /setspawn.");
            }
        }
        if (plugin.getConfig().getBoolean("worlds.farmworld.enabled", true) && Bukkit.getWorld(farmName()) == null) createFarmworld();
        int sleep = plugin.getConfig().getInt("worlds.sleep-percentage", 30);
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NORMAL) w.setGameRule(GameRule.PLAYERS_SLEEPING_PERCENTAGE, sleep);
        }
    }

    private World createFarmworld() {
        WorldCreator wc = new WorldCreator(farmName()).seed(ThreadLocalRandom.current().nextLong());
        World w = wc.createWorld();
        if (w != null) {
            w.getWorldBorder().setCenter(0, 0);
            w.getWorldBorder().setSize(plugin.getConfig().getDouble("worlds.farmworld.border", 6000));
            w.setGameRule(GameRule.PLAYERS_SLEEPING_PERCENTAGE, plugin.getConfig().getInt("worlds.sleep-percentage", 30));
        }
        return w;
    }

    private void checkReset() {
        if (!plugin.getConfig().getBoolean("worlds.farmworld.enabled", true)) return;
        LocalDateTime now = LocalDateTime.now(RewardService.ZONE);
        DayOfWeek day = DayOfWeek.valueOf(plugin.getConfig().getString("worlds.farmworld.reset-day", "SUNDAY").toUpperCase());
        String time = plugin.getConfig().getString("worlds.farmworld.reset-time", "04:00");
        String hhmm = String.format("%02d:%02d", now.getHour(), now.getMinute());
        if (now.getDayOfWeek() == day) {
            if (hhmm.equals(time)) resetFarmworld();
            else if (now.getMinute() % 15 == 0 && now.getHour() == Integer.parseInt(time.split(":")[0]) - 1) {
                Msg.broadcast("farmwelt.warning", "{prefix}&eDie Farmwelt wird in einer Stunde zurueckgesetzt!");
            }
        }
    }

    /** Spieler raus, Welt entladen, Ordner asynchron loeschen, neu erzeugen. */
    public void resetFarmworld() {
        if (resetting) return;
        World w = Bukkit.getWorld(farmName());
        resetting = true;
        Msg.broadcast("farmwelt.resetting", "{prefix}&eDie Farmwelt wird jetzt zurueckgesetzt...");
        if (w != null) {
            for (Player p : w.getPlayers()) p.teleport(plugin.teleport().spawn());
            if (!Bukkit.unloadWorld(w, false)) {
                plugin.getLogger().warning("Farmwelt konnte nicht entladen werden - Reset abgebrochen.");
                resetting = false;
                return;
            }
        }
        File folder = new File(Bukkit.getWorldContainer(), farmName());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                deleteRecursive(folder.toPath());
            } catch (IOException e) {
                plugin.getLogger().severe("Farmwelt-Ordner konnte nicht geloescht werden: " + e.getMessage());
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                createFarmworld();
                resetting = false;
                plugin.webhook().send("server", "Farmwelt zurueckgesetzt", "Die Farmwelt wurde neu generiert.", 0x26A69A);
                Msg.broadcast("farmwelt.reset-done", "{prefix}&aDie Farmwelt wurde neu generiert! &f/farmwelt");
            });
        });
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }

    private void spawnParticles() {
        if (!plugin.getConfig().getBoolean("spawn-particles.enabled", true)) return;
        Location sp = plugin.teleport() == null ? null : plugin.teleport().spawn();
        if (sp == null || !sp.isChunkLoaded() || sp.getWorld().getPlayers().isEmpty()) return;
        double t = (System.currentTimeMillis() % 4000) / 4000.0 * Math.PI * 2;
        for (int i = 0; i < 3; i++) {
            double a = t + i * (Math.PI * 2 / 3);
            Location l = sp.clone().add(Math.cos(a) * 2.5, 0.2 + (Math.sin(t * 2) + 1) * 0.8, Math.sin(a) * 2.5);
            sp.getWorld().spawnParticle(Particle.END_ROD, l, 1, 0, 0, 0, 0);
            sp.getWorld().spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, new Particle.DustOptions(org.bukkit.Color.fromRGB(0, 206, 209), 1.2f));
        }
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getLocation().getWorld().getName().equals(spawnWorldName()) && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPlayedBefore()) {
            e.joinMessage(null);
            Msg.broadcast("join.first", "&8[&a+&8] &b{name} &7ist zum ersten Mal auf &b&lMine&f&lSMP&7! Willkommen!", "name", p.getName());
        } else {
            e.joinMessage(null);
            String msg = Msg.get("join.message", "&8[&a+&8] &7{name}", "name", p.getName());
            if (plugin.staff() != null && plugin.staff().isVanished(p)) return;
            for (Player o : Bukkit.getOnlinePlayers()) if (plugin.data().get(o).setting("join-messages", true)) o.sendMessage(de.minesmp.core.util.Text.c(msg));
        }
        Msg.send(p, "join.welcome", "&8&m                                        \n  &7Willkommen auf &b&lMine&f&lSMP&7, &f{name}&7!\n  &7Hilfe: &b/help &8| &7Discord: &b/discord\n&8&m                                        ", "name", p.getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        e.quitMessage(null);
        if (plugin.staff() != null && plugin.staff().isVanished(e.getPlayer())) return;
        String msg = Msg.get("quit.message", "&8[&c-&8] &7{name}", "name", e.getPlayer().getName());
        for (Player o : Bukkit.getOnlinePlayers()) if (o != e.getPlayer() && plugin.data().get(o).setting("join-messages", true)) o.sendMessage(de.minesmp.core.util.Text.c(msg));
    }

    /** Leere Welt fuer den Spawn. */
    public static final class VoidGenerator extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
        @Override public Location getFixedSpawnLocation(World world, Random random) { return new Location(world, 0.5, 100, 0.5); }
    }
}
