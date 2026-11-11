package de.minesmp.core.events;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.utility.RewardService;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.boss.BarColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PvE-Modus "Kristallgruft" (/gruft): allein oder in einer Gruppe mehrere Wellen ueberleben und den Boss besiegen.
 * Ablauf pro Welle: Kreaturen besiegen -> an einem Laternenmast stehen bleiben, bis er brennt (Lichtfresser stoeren).
 * Letzte Welle: Boss mit Schockwelle und Verstaerkung. Gespielt wird mit der eigenen Ausruestung, nichts geht verloren.
 * Die Arenen liegen in der Spawn-Welt und kommen aus dem Bauplan (spawn/layout.yml -> pve).
 */
public final class PveService implements Listener {
    private record Arena(String id, Location spawn, Location center, double radius, List<Location> mobSpawns, List<Location> lanterns, Location boss) {}
    private record MobType(String name, EntityType type, double health, double speed, double damage) {}
    private enum Stage { PREPARE, FIGHT, LANTERN, BOSS, ENDED }

    private final class Run {
        final Arena arena;
        final List<UUID> players = new ArrayList<>();
        final Set<UUID> alive = new HashSet<>();
        final Set<UUID> mobs = new HashSet<>();
        final List<String> toSpawn = new ArrayList<>();
        final Set<Integer> lit = new HashSet<>();
        final Map<UUID, Location> returnTo = new HashMap<>();
        int wave, lantern = -1, stageSeconds, abilityIn = 10;
        double progress;
        boolean continued, bossDead;
        Stage stage = Stage.PREPARE;
        Entity wisp;
        LivingEntity boss;
        Run(Arena arena) { this.arena = arena; }
        List<Player> online() { List<Player> l = new ArrayList<>(); for (UUID id : players) { Player p = Bukkit.getPlayer(id); if (p != null) l.add(p); } return l; }
        List<Player> fighters() { List<Player> l = new ArrayList<>(); for (Player p : online()) if (alive.contains(p.getUniqueId())) l.add(p); return l; }
    }

    private final MineSMP plugin;
    private final NamespacedKey mobKey;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<String, Run> runs = new HashMap<>();
    private final Map<UUID, Run> byPlayer = new HashMap<>();
    private final Map<UUID, List<UUID>> parties = new HashMap<>(); // Leiter -> Mitglieder (inkl. Leiter)
    private final Map<UUID, UUID> invites = new HashMap<>();
    private final Map<String, MobType> mobTypes = new HashMap<>();

    public PveService(MineSMP plugin) {
        this.plugin = plugin;
        this.mobKey = new NamespacedKey(plugin, "pve_mob");
        mobTypes.put("kriecher", new MobType("&cKriecher", EntityType.CAVE_SPIDER, 16, 1.25, 3));
        mobTypes.put("grubenschreck", new MobType("&6Grubenschreck", EntityType.HUSK, 28, 1.1, 5));
        mobTypes.put("schattenwache", new MobType("&8Schattenwache", EntityType.WITHER_SKELETON, 36, 1.05, 7));
        mobTypes.put("lichtfresser", new MobType("&eLichtfresser", EntityType.SILVERFISH, 8, 1.3, 2));
        plugin.listen(this);
        plugin.command(new SimpleCommand("gruft", "minesmp.pve", "PvE: Die Kristallgruft", this::cmd, (s, a) -> {
            if (a.length == 1) return List.of("start", "invite", "accept", "leave", "info");
            if (a.length == 2 && a[0].equalsIgnoreCase("invite")) return null;
            return List.of();
        }, "pve", "kristallgruft"));
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getWorlds().forEach(w -> w.getLivingEntities().forEach(e -> {
            if (e.getPersistentDataContainer().has(mobKey)) e.remove();
        })));
        Bukkit.getScheduler().runTaskTimer(plugin, this::second, 20, 20);
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("pve");
        return s == null ? new org.bukkit.configuration.MemoryConfiguration() : s;
    }

    private String title() { return cfg().getString("name", "&5&lDie Kristallgruft"); }

    private void loadArenas() {
        if (!arenas.isEmpty() || plugin.spawn() == null || !plugin.spawn().isBuilt() || plugin.spawn().layout() == null) return;
        ConfigurationSection as = plugin.spawn().layout().getConfigurationSection("pve.arenas");
        if (as == null) return;
        for (String id : as.getKeys(false)) {
            Location spawn = plugin.spawn().toLocation(as.getList(id + ".spawn")), center = plugin.spawn().toLocation(as.getList(id + ".center"));
            if (spawn == null || center == null) continue;
            List<Location> mobs = new ArrayList<>(), lanterns = new ArrayList<>();
            for (Object o : as.getList(id + ".mobs", List.of())) { Location l = plugin.spawn().toLocation(o); if (l != null) mobs.add(l); }
            for (Object o : as.getList(id + ".lanterns", List.of())) { Location l = plugin.spawn().toLocation(o); if (l != null) lanterns.add(l); }
            Location boss = plugin.spawn().toLocation(as.getList(id + ".boss"));
            if (mobs.isEmpty()) mobs.add(center);
            arenas.put(id, new Arena(id, spawn, center, as.getDouble(id + ".radius", 30), mobs, lanterns, boss == null ? center : boss));
        }
    }

    public boolean inRun(Player p) { return byPlayer.containsKey(p.getUniqueId()); }

    private int waveCount() { return cfg().getMapList("waves").size() + 1; }

    // ================================================================ Befehl / Gruppe
    private void cmd(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        if (!cfg().getBoolean("enabled", true)) { Msg.send(p, "pve.disabled", "{prefix}&cDie Gruft ist gerade geschlossen."); return; }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> start(p);
            case "invite", "einladen" -> {
                if (a.length < 2) { Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/gruft invite <Spieler>"); return; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null || t.equals(p) || !p.canSee(t)) { Msg.send(p, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a[1]); return; }
                List<UUID> party = parties.computeIfAbsent(leaderOf(p), k -> new ArrayList<>(List.of(k)));
                if (!party.get(0).equals(p.getUniqueId())) { Msg.send(p, "pve.not-leader", "{prefix}&cNur der Gruppenleiter kann einladen."); return; }
                if (party.size() >= cfg().getInt("max-players", 4)) { Msg.send(p, "pve.party-full", "{prefix}&cDie Gruppe ist voll."); return; }
                invites.put(t.getUniqueId(), p.getUniqueId());
                Msg.send(p, "pve.invited", "{prefix}&7Einladung an &f{name} &7gesendet.", "name", t.getName());
                t.sendMessage(Text.c(Msg.get("pve.invite", "{prefix}&f{name} &7laedt dich in die {gruft} &7ein. ", "name", p.getName(), "gruft", title()))
                        .append(Text.c("&a&l[ANNEHMEN]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/gruft accept"))));
            }
            case "accept", "annehmen" -> {
                UUID leader = invites.remove(p.getUniqueId());
                List<UUID> party = leader == null ? null : parties.get(leader);
                if (party == null || Bukkit.getPlayer(leader) == null) { Msg.send(p, "pve.no-invite", "{prefix}&cKeine gueltige Einladung."); return; }
                if (party.size() >= cfg().getInt("max-players", 4)) { Msg.send(p, "pve.party-full", "{prefix}&cDie Gruppe ist voll."); return; }
                leaveParty(p);
                party.add(p.getUniqueId());
                for (UUID id : party) { Player o = Bukkit.getPlayer(id); if (o != null) Msg.send(o, "pve.joined", "{prefix}&f{name} &7ist der Gruppe beigetreten &8({n}/{max})", "name", p.getName(), "n", String.valueOf(party.size()), "max", String.valueOf(cfg().getInt("max-players", 4))); }
            }
            case "leave", "verlassen" -> {
                Run r = byPlayer.get(p.getUniqueId());
                if (r != null) { removeFromRun(r, p, true); Msg.send(p, "pve.left", "{prefix}&7Du hast die Gruft verlassen."); }
                else { leaveParty(p); Msg.send(p, "pve.party-left", "{prefix}&7Du hast die Gruppe verlassen."); }
            }
            default -> openMenu(p);
        }
    }

    private UUID leaderOf(Player p) {
        for (Map.Entry<UUID, List<UUID>> en : parties.entrySet()) if (en.getValue().contains(p.getUniqueId())) return en.getKey();
        return p.getUniqueId();
    }

    private void leaveParty(Player p) {
        UUID leader = leaderOf(p);
        List<UUID> party = parties.get(leader);
        if (party == null) return;
        if (leader.equals(p.getUniqueId())) parties.remove(leader);
        else party.remove(p.getUniqueId());
    }

    private void openMenu(Player p) {
        loadArenas();
        PlayerData d = plugin.data().get(p);
        List<UUID> party = parties.getOrDefault(leaderOf(p), List.of(p.getUniqueId()));
        Menu m = new Menu(3, "&8" + Text.strip(Text.gradients(title()))) {};
        List<String> info = new ArrayList<>(List.of("&7Ueberlebe &f" + waveCount() + " Wellen &7und besiege", "&7den " + cfg().getString("boss.name", "&5&lGruftwaechter") + "&7 - allein oder",
                "&7mit bis zu &f" + (cfg().getInt("max-players", 4) - 1) + " Freunden&7.", "&8", "&7Du spielst mit deiner Ausruestung.", "&aBeim Tod geht nichts verloren.", "&8",
                "&7Deine Abschluesse: &d" + d.pveWins, "&7Belohnte Laeufe heute: &f" + rewardedToday(d) + "&7/&f" + cfg().getInt("rewarded-runs-per-day", 3)));
        m.set(4, Items.glow(Items.of(Material.AMETHYST_CLUSTER, 1, title(), info)));
        boolean leader = party.get(0).equals(p.getUniqueId());
        m.set(11, Items.glow(Items.of(Material.NETHERITE_SWORD, 1, leader ? "&a&lGruft betreten" : "&7Nur der Gruppenleiter startet", List.of(party.size() == 1 ? "&7Allein starten" : "&7Mit deiner Gruppe starten (" + party.size() + ")",
                "&7Freie Grueften: &f" + freeArenas() + "&7/&f" + arenas.size()))), e -> { Menu.close(p); start(p); });
        List<String> names = new ArrayList<>();
        for (UUID id : party) { Player o = Bukkit.getPlayer(id); names.add((o != null ? "&a● &f" : "&8● ") + (o != null ? o.getName() : "?") + (id.equals(party.get(0)) ? " &8(Leiter)" : "")); }
        names.add("&8");
        names.add("&7Einladen: &b/gruft invite <Spieler>");
        names.add("&7Verlassen: &b/gruft leave");
        m.set(13, Items.of(Material.PLAYER_HEAD, Math.max(1, party.size()), "&b&lGruppe &7(" + party.size() + "/" + cfg().getInt("max-players", 4) + ")", names));
        m.set(15, Items.of(Material.CHEST, 1, "&6&lBelohnungen", List.of("&7Fuer jeden Abschluss:", cfg().getString("reward-display", "&aMineCoins, Gems, Sterne, Kristalle, Schluessel"),
                "&7Mit Glueck: &dSpawner, Verzauberungsbuch", "&8Voll belohnt werden die ersten " + cfg().getInt("rewarded-runs-per-day", 3) + " Laeufe am Tag.")));
        m.fillEmpty();
        m.open(p);
    }

    private int freeArenas() {
        int n = 0;
        for (String id : arenas.keySet()) if (!runs.containsKey(id)) n++;
        return n;
    }

    private int rewardedToday(PlayerData d) {
        String[] p = d.pveRewarded.split(":");
        return p.length == 2 && p[0].equals(RewardService.today()) ? Integer.parseInt(p[1]) : 0;
    }

    // ================================================================ Start / Ende
    private void start(Player p) {
        loadArenas();
        if (inRun(p)) return;
        UUID leader = leaderOf(p);
        if (!leader.equals(p.getUniqueId())) { Msg.send(p, "pve.not-leader-start", "{prefix}&cNur der Gruppenleiter kann starten."); return; }
        Arena arena = null;
        for (Arena a : arenas.values()) if (!runs.containsKey(a.id)) { arena = a; break; }
        if (arenas.isEmpty()) { Msg.send(p, "pve.no-arena", "{prefix}&cDie Gruft ist noch nicht gebaut (Spawn neu bauen: /minesmp buildspawn confirm)."); return; }
        if (arena == null) { Msg.send(p, "pve.busy", "{prefix}&cAlle Grueften sind gerade belegt. Versuch es gleich nochmal."); return; }
        List<Player> members = new ArrayList<>();
        for (UUID id : parties.getOrDefault(leader, List.of(leader))) {
            Player o = Bukkit.getPlayer(id);
            if (o == null) continue;
            if (plugin.duels().inDuel(o) || plugin.combat().isTagged(o) || o.isDead() || inRun(o)) { Msg.send(p, "pve.member-busy", "{prefix}&c{name} ist gerade im Kampf.", "name", o.getName()); return; }
            members.add(o);
        }
        Run r = new Run(arena);
        runs.put(arena.id, r);
        for (Location l : arena.lanterns) setLit(l, false);
        for (Player o : members) {
            r.players.add(o.getUniqueId());
            r.alive.add(o.getUniqueId());
            r.returnTo.put(o.getUniqueId(), o.getLocation());
            byPlayer.put(o.getUniqueId(), r);
            o.closeInventory();
            plugin.ranks().teleport(o, arena.spawn);
            o.showTitle(Title.title(Text.c(title()), Text.c("&7" + waveCount() + " Wellen &8· &7" + members.size() + (members.size() == 1 ? " Spieler" : " Spieler")),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(500))));
            o.playSound(o.getLocation(), Sound.AMBIENT_CAVE, 1, 0.8f);
        }
        nextWave(r);
    }

    private void nextWave(Run r) {
        r.wave++;
        r.stage = Stage.PREPARE;
        r.stageSeconds = 0;
        r.progress = 0;
        r.lantern = -1;
        // Gefallene kehren zurueck
        for (Player p : r.online()) {
            if (r.alive.add(p.getUniqueId()) || p.getGameMode() == GameMode.SPECTATOR) {
                p.setGameMode(GameMode.SURVIVAL);
                plugin.ranks().teleport(p, r.arena.spawn);
            }
            heal(p, 6);
        }
    }

    private static void heal(Player p, double amount) {
        if (p.isDead()) return;
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(Math.min(max == null ? 20 : max.getValue(), p.getHealth() + amount));
    }

    private void finish(Run r, boolean success) {
        if (r.stage == Stage.ENDED) return;
        r.stage = Stage.ENDED;
        runs.remove(r.arena.id);
        clearMobs(r);
        for (Location l : r.arena.lanterns) setLit(l, false);
        List<Player> online = r.online();
        for (Player p : online) {
            byPlayer.remove(p.getUniqueId());
            plugin.scoreboard().hideBossbar(p, "pve");
            p.removePotionEffect(PotionEffectType.DARKNESS);
            if (success) {
                p.showTitle(Title.title(Text.c("&d&lGESCHAFFT!"), Text.c(title() + " &7ist bezwungen"), Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(3500), Duration.ofMillis(600))));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1, 1);
                reward(p, online.size());
            } else {
                p.showTitle(Title.title(Text.c("&c&lDie Gruft hat gewonnen"), Text.c("&7Gescheitert in Welle &f" + r.wave + " &7von &f" + waveCount()),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(3500), Duration.ofMillis(600))));
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.6f, 0.7f);
            }
            Location back = backLocation(r, p);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                if (p.isDead()) p.spigot().respawn();
                p.setGameMode(GameMode.SURVIVAL);
                plugin.ranks().teleport(p, back);
            }, success ? 80 : 60);
        }
        for (UUID id : r.players) byPlayer.remove(id);
        if (success && !online.isEmpty()) {
            List<String> names = new ArrayList<>();
            online.forEach(p -> names.add(p.getName()));
            Msg.broadcast("pve.cleared", "{prefix}&f{names} &7{verb} {gruft} &7bezwungen!", "names", String.join("&7, &f", names), "verb", names.size() == 1 ? "hat" : "haben", "gruft", title());
        }
    }

    private Location backLocation(Run r, Player p) {
        Location lobby = plugin.spawn() == null ? null : plugin.spawn().point("pve-lobby");
        return lobby != null ? lobby : r.returnTo.getOrDefault(p.getUniqueId(), p.getWorld().getSpawnLocation());
    }

    private void reward(Player p, int partySize) {
        PlayerData d = plugin.data().get(p);
        d.pveWins++;
        int today = rewardedToday(d);
        d.dirty = true;
        if (today >= cfg().getInt("rewarded-runs-per-day", 3)) {
            double money = cfg().getDouble("reward-after-limit-money", 1000);
            plugin.eco().deposit(p, money, "PVE", "Gruft (Tageslimit)");
            Msg.send(p, "pve.reward-limit", "{prefix}&7Tageslimit erreicht: nur noch &a{money}&7. Morgen gibt es wieder die volle Belohnung.", "money", plugin.eco().format(money));
            return;
        }
        d.pveRewarded = RewardService.today() + ":" + (today + 1);
        plugin.rewards().give(p, cfg().getConfigurationSection("reward"), "Kristallgruft");
        for (Map<?, ?> bonus : cfg().getMapList("bonus-rewards")) {
            double chance = bonus.get("chance") instanceof Number n ? n.doubleValue() : 0;
            if (ThreadLocalRandom.current().nextDouble() >= chance) continue;
            org.bukkit.configuration.MemoryConfiguration mc = new org.bukkit.configuration.MemoryConfiguration();
            if (bonus.get("reward") instanceof Map<?, ?> rm) rm.forEach((k, v) -> { if (v instanceof Map<?, ?> mv) mc.createSection(String.valueOf(k), mv); else mc.set(String.valueOf(k), v); });
            plugin.rewards().give(p, mc, "Gruft-Bonus");
            if (bonus.get("broadcast") != null) Msg.broadcast("pve.bonus", "{prefix}&f{name} &7findet in der Gruft: {what}", "name", p.getName(), "what", String.valueOf(bonus.get("broadcast")));
        }
    }

    private void removeFromRun(Run r, Player p, boolean teleport) {
        r.players.remove(p.getUniqueId());
        r.alive.remove(p.getUniqueId());
        byPlayer.remove(p.getUniqueId());
        plugin.scoreboard().hideBossbar(p, "pve");
        p.removePotionEffect(PotionEffectType.DARKNESS);
        if (teleport && p.isOnline()) {
            if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL);
            plugin.ranks().teleport(p, backLocation(r, p));
        }
        if (r.players.isEmpty()) finish(r, false);
    }

    private void clearMobs(Run r) {
        for (UUID id : r.mobs) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        r.mobs.clear();
        r.toSpawn.clear();
        if (r.wisp != null) { r.wisp.remove(); r.wisp = null; }
        if (r.boss != null) { r.boss.remove(); r.boss = null; }
    }

    // ================================================================ Ablauf (jede Sekunde)
    private void second() {
        for (Run r : new ArrayList<>(runs.values())) {
            try {
                tick(r);
            } catch (Exception ex) {
                plugin.getLogger().warning("Fehler in der Gruft: " + ex);
                ex.printStackTrace();
                finish(r, false);
            }
        }
    }

    private void tick(Run r) {
        if (r.stage == Stage.ENDED) return;
        r.stageSeconds++;
        List<Player> fighters = r.fighters();
        if (r.online().isEmpty()) { finish(r, false); return; }
        // Alle gefallen: einmal weiterspielen, sonst gescheitert
        if (fighters.isEmpty()) {
            if (!r.continued) {
                r.continued = true;
                clearMobs(r);
                r.wave--;
                for (Player p : r.online()) {
                    p.showTitle(Title.title(Text.c("&e&lZweite Chance"), Text.c("&7Welle " + (r.wave + 1) + " beginnt neu &8· &7Weiterspielen 1/1"),
                            Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(400))));
                }
                nextWave(r);
            } else finish(r, false);
            return;
        }
        // Niemand verlaesst die Arena
        for (Player p : r.online()) {
            if (!p.getWorld().equals(r.arena.center.getWorld()) || p.getLocation().distanceSquared(r.arena.center) > r.arena.radius * r.arena.radius) plugin.ranks().teleport(p, r.arena.spawn);
        }
        r.mobs.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || e.isDead() || !e.isValid(); });
        retarget(r, fighters);
        boolean bossWave = r.wave >= waveCount();
        Map<?, ?> wave = bossWave ? Map.of() : cfg().getMapList("waves").get(r.wave - 1);
        boolean dark = "darkness".equalsIgnoreCase(String.valueOf(wave.get("modifier")));
        String bar = title() + " &8┃ &fWelle " + r.wave + "&7/&f" + waveCount();
        switch (r.stage) {
            case PREPARE -> {
                actionbar(r, "&eMach dich bereit, gleich kommen Kreaturen");
                if (r.stageSeconds == 1) {
                    String sub = bossWave ? cfg().getString("boss.name", "&5&lGruftwaechter") : wave.get("title") == null ? "&7Besiege die Kreaturen" : "&7" + wave.get("title");
                    for (Player p : r.online()) {
                        p.showTitle(Title.title(Text.c(bossWave ? "&5&lBOSS" : "&6&lWELLE " + r.wave), Text.c(sub), Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2200), Duration.ofMillis(400))));
                        p.playSound(p.getLocation(), bossWave ? Sound.ENTITY_WITHER_SPAWN : Sound.EVENT_RAID_HORN, 0.7f, 1);
                    }
                }
                if (r.stageSeconds >= 6) {
                    r.stageSeconds = 0;
                    if (bossWave) { r.stage = Stage.BOSS; spawnBoss(r); }
                    else {
                        r.stage = Stage.FIGHT;
                        double scale = 1 + 0.6 * (r.online().size() - 1);
                        if (wave.get("mobs") instanceof Map<?, ?> mm) mm.forEach((k, v) -> { for (int i = 0; i < Math.round(((Number) v).intValue() * scale); i++) r.toSpawn.add(String.valueOf(k)); });
                        java.util.Collections.shuffle(r.toSpawn);
                    }
                }
            }
            case FIGHT -> {
                if (dark) for (Player p : fighters) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, true, false, false));
                int maxAlive = 6 + 3 * r.online().size();
                for (int i = 0; i < 3 && !r.toSpawn.isEmpty() && r.mobs.size() < maxAlive; i++) spawnMob(r, r.toSpawn.remove(r.toSpawn.size() - 1));
                int left = r.mobs.size() + r.toSpawn.size();
                actionbar(r, "&6Besiege die Kreaturen &8· &7noch &f" + left);
                if (left <= 3) for (UUID id : r.mobs) { Entity e = Bukkit.getEntity(id); if (e != null) e.setGlowing(true); }
                if (left == 0) {
                    r.stage = Stage.LANTERN;
                    r.stageSeconds = 0;
                    r.progress = 0;
                    r.lantern = -1;
                    for (int i = 0; i < r.arena.lanterns.size(); i++) if (!r.lit.contains(i)) { r.lantern = i; break; }
                    if (r.lantern < 0) { nextWave(r); return; }
                    Location l = r.arena.lanterns.get(r.lantern);
                    r.wisp = l.getWorld().spawn(l.clone().add(0.5, 2.2, 0.5), Allay.class, a -> {
                        a.setAI(false);
                        a.setInvulnerable(true);
                        a.setGlowing(true);
                        a.setPersistent(false);
                        a.setSilent(true);
                        a.customName(Text.c("&bIrrlicht"));
                        a.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, r.arena.id);
                    });
                    for (Player p : r.online()) p.playSound(p.getLocation(), Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 1, 1.2f);
                }
            }
            case LANTERN -> {
                Location l = r.arena.lanterns.get(r.lantern).clone().add(0.5, 0, 0.5);
                int near = 0;
                for (Player p : fighters) if (p.getLocation().distanceSquared(l) <= 4 * 4) near++;
                int eaters = 0;
                for (UUID id : r.mobs) { Entity e = Bukkit.getEntity(id); if (e != null && e.getType() == EntityType.SILVERFISH && e.getLocation().distanceSquared(l) <= 6 * 6) eaters++; }
                if (near > 0) r.progress += 4 + 2 * near;
                r.progress = Math.max(0, r.progress - eaters * 3);
                if (r.stageSeconds % 4 == 1 && r.mobs.size() < 3 + 2 * r.online().size()) {
                    spawnMob(r, "lichtfresser");
                    if (r.wave >= 2) spawnMob(r, "kriecher");
                }
                l.getWorld().spawnParticle(Particle.FLAME, l.clone().add(0, 2.4, 0), 6, 0.25, 0.25, 0.25, 0.01);
                for (Player p : fighters) {
                    boolean close = p.getLocation().distanceSquared(l) <= 4 * 4;
                    Msg.markActionbar(p, 1500);
                    p.sendActionBar(Text.c(close ? "&eBleib am Mast stehen &f" + (int) Math.min(100, r.progress) + " %" + (eaters > 0 ? " &8· &c" + eaters + " Lichtfresser!" : "")
                            : "&bFolge dem Irrlicht zum Laternenmast " + arrow(p, l) + " &f" + (int) p.getLocation().distance(l) + " m"));
                }
                if (r.progress >= 100) {
                    r.lit.add(r.lantern);
                    setLit(r.arena.lanterns.get(r.lantern), true);
                    if (r.wisp != null) { r.wisp.remove(); r.wisp = null; }
                    l.getWorld().spawnParticle(Particle.FIREWORK, l.clone().add(0, 2, 0), 60, 0.4, 0.8, 0.4, 0.08);
                    for (Player p : r.online()) { p.playSound(p.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1, 1.2f); heal(p, 8); }
                    clearMobs(r);
                    nextWave(r);
                    return;
                }
            }
            case BOSS -> {
                if (r.bossDead) { finish(r, true); return; }
                if (r.boss == null || !r.boss.isValid()) spawnBoss(r);
                plugin.models().ensure(r.boss, cfg().getString("boss.model", "steintitan"), cfg().getDouble("boss.model-scale", 0.16));
                AttributeInstance max = r.boss.getAttribute(Attribute.MAX_HEALTH);
                double ratio = r.boss.getHealth() / (max == null ? 1 : max.getValue());
                bar = cfg().getString("boss.name", "&5&lGruftwaechter") + " &8┃ &c" + (int) Math.ceil(r.boss.getHealth()) + " ❤";
                for (Player p : r.online()) plugin.scoreboard().bossbar(p, "pve", bar, Math.max(0, Math.min(1, ratio)), BarColor.PURPLE);
                actionbar(r, "&5Besiege den " + Text.strip(Text.gradients(cfg().getString("boss.name", "Gruftwaechter"))));
                if (--r.abilityIn == 2) {
                    plugin.models().animate(r.boss, "attack");
                    r.boss.getWorld().spawnParticle(Particle.WITCH, r.boss.getLocation().add(0, 1, 0), 80, 3, 0.3, 3, 0.01);
                    for (Player p : fighters) p.playSound(r.boss.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.4f, 1);
                    for (Player p : fighters) { Msg.markActionbar(p, 2500); p.sendActionBar(Text.c("&c&lSchockwelle! &7Weg vom Boss!")); }
                } else if (r.abilityIn <= 0) {
                    r.abilityIn = 12;
                    Location bl = r.boss.getLocation();
                    bl.getWorld().spawnParticle(Particle.SONIC_BOOM, bl.clone().add(0, 1, 0), 3, 1.5, 0.3, 1.5, 0);
                    bl.getWorld().playSound(bl, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 1);
                    for (Player p : fighters) {
                        double dist = p.getLocation().distance(bl);
                        if (dist > 6.5) continue;
                        p.damage(cfg().getDouble("boss.shockwave-damage", 7), r.boss);
                        Vector v = p.getLocation().toVector().subtract(bl.toVector()).setY(0);
                        if (v.lengthSquared() < 0.01) v = new Vector(1, 0, 0);
                        p.setVelocity(v.normalize().multiply(1.3).setY(0.5));
                    }
                    for (int i = 0; i < 1 + r.online().size(); i++) spawnMob(r, i % 2 == 0 ? "kriecher" : "grubenschreck");
                }
                return;
            }
            default -> { }
        }
        for (Player p : r.online()) plugin.scoreboard().bossbar(p, "pve", bar, Math.max(0.02, Math.min(1, r.wave / (double) waveCount())), dark ? BarColor.PURPLE : BarColor.BLUE);
    }

    private void actionbar(Run r, String text) {
        for (Player p : r.online()) { Msg.markActionbar(p, 1500); p.sendActionBar(Text.c(text)); }
    }

    /** Pfeil zur Richtung des Ziels, relativ zur Blickrichtung. */
    private static String arrow(Player p, Location target) {
        Vector to = target.toVector().subtract(p.getLocation().toVector());
        double angle = Math.toDegrees(Math.atan2(-to.getX(), to.getZ())) - p.getLocation().getYaw();
        angle = ((angle % 360) + 360) % 360;
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        return arrows[(int) Math.round(angle / 45.0) % 8];
    }

    private static void setLit(Location l, boolean lit) {
        Block b = l.getBlock();
        if (b.getBlockData() instanceof Lightable li) {
            li.setLit(lit);
            b.setBlockData(li, false);
        }
    }

    // ================================================================ Kreaturen
    private void spawnMob(Run r, String typeId) {
        MobType t = mobTypes.get(typeId.toLowerCase(Locale.ROOT));
        if (t == null) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Location l = r.arena.mobSpawns.get(rnd.nextInt(r.arena.mobSpawns.size())).clone().add(rnd.nextDouble(-1.2, 1.2) + 0.5, 0, rnd.nextDouble(-1.2, 1.2) + 0.5);
        double hpScale = 1 + 0.15 * (r.wave - 1);
        Entity e = l.getWorld().spawnEntity(l, t.type, CreatureSpawnEvent.SpawnReason.CUSTOM, en -> {
            en.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, r.arena.id);
            en.setPersistent(false);
            if (en instanceof LivingEntity le) {
                le.setRemoveWhenFarAway(false);
                set(le, Attribute.MAX_HEALTH, t.health * hpScale);
                le.setHealth(t.health * hpScale);
                AttributeInstance sp = le.getAttribute(Attribute.MOVEMENT_SPEED);
                if (sp != null) sp.setBaseValue(sp.getBaseValue() * t.speed);
                set(le, Attribute.ATTACK_DAMAGE, t.damage);
                set(le, Attribute.FOLLOW_RANGE, 48);
                if (le.getEquipment() != null) {
                    le.getEquipment().setItemInMainHandDropChance(0);
                    if (t.type == EntityType.HUSK) le.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_PICKAXE));
                }
                if (le instanceof org.bukkit.entity.Ageable ag) ag.setAdult();
                le.setCustomNameVisible(true);
                le.customName(Text.c(mobName(t.name, 1)));
            }
        });
        r.mobs.add(e.getUniqueId());
    }

    private static void set(LivingEntity le, Attribute a, double v) {
        AttributeInstance in = le.getAttribute(a);
        if (in != null) in.setBaseValue(v);
    }

    private static String mobName(String name, double ratio) {
        return name + " " + Text.progressBar(ratio, 8, "&a▌", "&8▌");
    }

    private void spawnBoss(Run r) {
        Location l = r.arena.boss.clone().add(0.5, 0, 0.5);
        double hp = cfg().getDouble("boss.health", 260) * (1 + 0.6 * (r.online().size() - 1));
        r.abilityIn = 10;
        r.boss = (LivingEntity) l.getWorld().spawnEntity(l, EntityType.WITHER_SKELETON, CreatureSpawnEvent.SpawnReason.CUSTOM, en -> {
            LivingEntity le = (LivingEntity) en;
            en.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, r.arena.id);
            en.setPersistent(false);
            le.setRemoveWhenFarAway(false);
            set(le, Attribute.MAX_HEALTH, hp);
            le.setHealth(hp);
            set(le, Attribute.SCALE, 1.8);
            set(le, Attribute.ATTACK_DAMAGE, cfg().getDouble("boss.damage", 10));
            set(le, Attribute.KNOCKBACK_RESISTANCE, 0.8);
            set(le, Attribute.FOLLOW_RANGE, 64);
            if (le.getEquipment() != null) {
                le.getEquipment().setItemInMainHand(new ItemStack(Material.NETHERITE_AXE));
                le.getEquipment().setHelmet(new ItemStack(Material.AMETHYST_CLUSTER));
                le.getEquipment().setItemInMainHandDropChance(0);
                le.getEquipment().setHelmetDropChance(0);
            }
            le.setGlowing(true);
            le.setCustomNameVisible(true);
            le.customName(Text.c(cfg().getString("boss.name", "&5&lGruftwaechter")));
        });
        r.mobs.add(r.boss.getUniqueId());
        plugin.models().ensure(r.boss, cfg().getString("boss.model", "steintitan"), cfg().getDouble("boss.model-scale", 0.16));
    }

    private void retarget(Run r, List<Player> fighters) {
        if (fighters.isEmpty()) return;
        for (UUID id : r.mobs) {
            if (!(Bukkit.getEntity(id) instanceof Mob m)) continue;
            LivingEntity t = m.getTarget();
            if (t instanceof Player tp && r.alive.contains(tp.getUniqueId()) && !tp.isDead()) continue;
            Player best = null;
            double bd = Double.MAX_VALUE;
            for (Player p : fighters) { double d = p.getLocation().distanceSquared(m.getLocation()); if (d < bd) { bd = d; best = p; } }
            if (best != null) m.setTarget(best);
        }
    }

    // ================================================================ Events
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(mobKey)) e.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le) || e.getEntity() instanceof Player || !le.getPersistentDataContainer().has(mobKey)) return;
        if (le.getType() == EntityType.ALLAY) return;
        AttributeInstance max = le.getAttribute(Attribute.MAX_HEALTH);
        double ratio = Math.max(0, (le.getHealth() - e.getFinalDamage()) / (max == null ? 20 : max.getValue()));
        for (MobType t : mobTypes.values()) if (t.type == le.getType() && (max == null || max.getValue() < 150)) { le.customName(Text.c(mobName(t.name, ratio))); break; }
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player || !e.getEntity().getPersistentDataContainer().has(mobKey)) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        Run r = runs.get(e.getEntity().getPersistentDataContainer().get(mobKey, PersistentDataType.STRING));
        if (r != null && r.boss != null && r.boss.getUniqueId().equals(e.getEntity().getUniqueId())) r.bossDead = true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        // Gruft-Kreaturen greifen nur Teilnehmer an
        String arena = e.getEntity().getPersistentDataContainer().get(mobKey, PersistentDataType.STRING);
        if (arena == null || e.getTarget() == null) return;
        Run r = runs.get(arena);
        if (!(e.getTarget() instanceof Player p) || r == null || !r.alive.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || !inRun(victim)) return;
        Player attacker = e.getDamager() instanceof Player p ? p : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p2 ? p2 : null;
        if (attacker != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Run r = byPlayer.get(p.getUniqueId());
        if (r == null) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        e.deathMessage(null);
        r.alive.remove(p.getUniqueId());
        for (Player o : r.online()) Msg.send(o, "pve.down", "&8[&5Gruft&8] &f{name} &7ist gefallen &8- &7kehrt in der naechsten Welle zurueck.", "name", p.getName());
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && p.isDead()) p.spigot().respawn(); }, 10);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Run r = byPlayer.get(p.getUniqueId());
        if (r == null) return;
        e.setRespawnLocation(r.arena.center.clone().add(0.5, 3, 0.5));
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && byPlayer.get(p.getUniqueId()) == r && !r.alive.contains(p.getUniqueId())) p.setGameMode(GameMode.SPECTATOR); });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpectate(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE && inRun(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        invites.remove(p.getUniqueId());
        Run r = byPlayer.get(p.getUniqueId());
        if (r != null) {
            if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL);
            p.teleport(backLocation(r, p));
            removeFromRun(r, p, false);
        }
        leaveParty(p);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!inRun(e.getPlayer()) || e.getPlayer().hasPermission("minesmp.staff.commands")) return;
        String m = e.getMessage().toLowerCase(Locale.ROOT);
        if (m.startsWith("/gruft") || m.startsWith("/pve") || m.startsWith("/msg ") || m.startsWith("/r ") || m.startsWith("/tell ")) return;
        e.setCancelled(true);
        Msg.send(e.getPlayer(), "pve.no-commands", "{prefix}&cIn der Gruft sind Befehle gesperrt. Verlassen: &f/gruft leave");
    }

    public void shutdown() {
        for (Run r : new ArrayList<>(runs.values())) {
            clearMobs(r);
            for (Location l : r.arena.lanterns) setLit(l, false);
            for (Player p : r.online()) {
                if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL);
                p.teleport(backLocation(r, p));
            }
        }
        runs.clear();
        byPlayer.clear();
    }
}
