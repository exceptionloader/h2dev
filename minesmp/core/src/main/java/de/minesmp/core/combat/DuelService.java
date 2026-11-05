package de.minesmp.core.combat;

import de.minesmp.core.MineSMP;
import de.minesmp.core.combat.KitService.Kit;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * 1v1-Duelle mit Kits in festen Arenen. Das eigene Inventar wird vorher gesichert (auch in der DB,
 * damit es nach einem Absturz beim naechsten Join zurueckkommt) und danach wiederhergestellt.
 */
public final class DuelService implements Listener {
    private enum Phase { COUNTDOWN, FIGHT, ENDED }

    private final class Duel {
        final Player a, b;
        final Kit kit;
        final String arena;
        final long start = System.currentTimeMillis();
        final BiConsumer<Player, Player> onEnd; // (Gewinner, Verlierer), Gewinner null = unentschieden
        Phase phase = Phase.COUNTDOWN;
        Duel(Player a, Player b, Kit kit, String arena, BiConsumer<Player, Player> onEnd) { this.a = a; this.b = b; this.kit = kit; this.arena = arena; this.onEnd = onEnd; }
        Player other(Player p) { return p.equals(a) ? b : a; }
    }

    private record Request(UUID from, String kit, long time) {}

    private final MineSMP plugin;
    private final Map<UUID, Duel> duels = new HashMap<>();
    private final Map<UUID, Request> requests = new HashMap<>();
    private final Set<String> busyArenas = new HashSet<>();
    private final Set<UUID> restoreOnRespawn = new HashSet<>();

    public DuelService(MineSMP plugin) {
        this.plugin = plugin;
        plugin.listen(this);
        plugin.command(new SimpleCommand("duel", "minesmp.duel", "1v1-Duelle", this::cmd, (s, a) -> {
            if (a.length == 1) { List<String> l = new ArrayList<>(List.of("accept", "deny", "stats")); Bukkit.getOnlinePlayers().forEach(p -> l.add(p.getName())); if (s.hasPermission("minesmp.admin.duel")) { l.add("setarena"); l.add("setffa"); } return l; }
            if (a.length == 2 && !a[0].equalsIgnoreCase("setarena")) return null;
            if (a.length == 3 && a[0].equalsIgnoreCase("setarena")) return List.of("1", "2");
            return List.of();
        }, "1v1", "duell"));
        plugin.command(new SimpleCommand("arena", "minesmp.arena", "PvP-Arena", (s, l, a) -> {
            if (!(s instanceof Player p)) return;
            Location loc = PlayerData.locFromString(plugin.getConfig().getString("duels.ffa-spawn", ""));
            if (loc == null) { Msg.send(p, "arena.not-set", "{prefix}&cDie PvP-Arena ist noch nicht eingerichtet."); return; }
            plugin.teleport().teleport(p, loc, "PvP-Arena");
        }, "pvp"));
    }

    public boolean inDuel(Player p) {
        return duels.containsKey(p.getUniqueId());
    }

    private void cmd(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        if (a.length == 0) { Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/duel <Spieler> [Kit] | accept <Spieler> | deny | stats"); return; }
        switch (a[0].toLowerCase()) {
            case "accept" -> accept(p, a.length > 1 ? a[1] : null);
            case "deny" -> { requests.remove(p.getUniqueId()); Msg.send(p, "duel.denied", "{prefix}&7Duellanfrage abgelehnt."); }
            case "stats" -> {
                PlayerData d = plugin.data().get(p);
                Msg.send(p, "duel.stats", "{prefix}&7Duelle: &a{w} Siege &8| &c{l} Niederlagen", "w", String.valueOf(d.duelWins), "l", String.valueOf(d.duelLosses));
            }
            case "setarena" -> {
                if (!p.hasPermission("minesmp.admin.duel") || a.length < 3) return;
                plugin.getConfig().set("duels.arenas." + a[1] + ".pos" + a[2], PlayerData.locToString(p.getLocation()));
                plugin.saveConfig();
                plugin.audit().log(p, "DUEL_ARENA", a[1] + " pos" + a[2]);
                Msg.send(p, "duel.arena-set", "{prefix}&aArena &f{name} &aPosition &f{pos} &agesetzt.", "name", a[1], "pos", a[2]);
            }
            case "setffa" -> {
                if (!p.hasPermission("minesmp.admin.duel")) return;
                plugin.getConfig().set("duels.ffa-spawn", PlayerData.locToString(p.getLocation()));
                plugin.saveConfig();
                Msg.send(p, "duel.ffa-set", "{prefix}&aPvP-Arena-Spawn gesetzt.");
            }
            default -> request(p, a[0], a.length > 1 ? a[1] : null);
        }
    }

    private void request(Player p, String targetName, String kitName) {
        Player t = Bukkit.getPlayerExact(targetName);
        if (t == null || t.equals(p) || !p.canSee(t)) { Msg.send(p, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", targetName); return; }
        if (kitName == null) { openKitSelect(p, t); return; }
        Kit k = plugin.kits().get(kitName);
        if (k == null || !k.duelOnly()) { Msg.send(p, "duel.unknown-kit", "{prefix}&cUnbekanntes Duell-Kit."); return; }
        if (!plugin.data().get(t).setting("duels", true)) { Msg.send(p, "duel.disabled", "{prefix}&f{name} &cnimmt keine Duelle an.", "name", t.getName()); return; }
        if (!plugin.cooldowns().tryUse(p.getUniqueId(), "duel-req", 5000)) return;
        requests.put(t.getUniqueId(), new Request(p.getUniqueId(), k.id(), System.currentTimeMillis()));
        Msg.send(p, "duel.sent", "{prefix}&7Duellanfrage an &f{name} &7gesendet (Kit: {kit}&7).", "name", t.getName(), "kit", k.name());
        t.sendMessage(Text.c(Msg.get("duel.received", "{prefix}&f{name} &7fordert dich zu einem Duell heraus (Kit: {kit}&7). ", "name", p.getName(), "kit", k.name()))
                .append(Text.c("&a&l[ANNEHMEN]").clickEvent(ClickEvent.runCommand("/duel accept " + p.getName())))
                .append(Text.c(" &c&l[ABLEHNEN]").clickEvent(ClickEvent.runCommand("/duel deny"))));
    }

    private void openKitSelect(Player p, Player t) {
        Menu m = new Menu(3, "&8Duell-Kit waehlen") {};
        int i = 10;
        for (Kit k : plugin.kits().duelKits()) {
            if (i > 16) break;
            m.set(i++, Items.of(k.icon(), 1, k.name(), List.of("&7Klicke, um &f" + t.getName() + " &7herauszufordern")), e -> {
                Menu.close(p);
                request(p, t.getName(), k.id());
            });
        }
        m.fillEmpty();
        m.open(p);
    }

    private void accept(Player p, String fromName) {
        Request r = requests.remove(p.getUniqueId());
        Player from = r == null ? null : Bukkit.getPlayer(r.from);
        if (from == null || (fromName != null && !from.getName().equalsIgnoreCase(fromName)) || System.currentTimeMillis() - r.time > 60_000) {
            Msg.send(p, "duel.no-request", "{prefix}&cKeine gueltige Duellanfrage.");
            return;
        }
        String err = start(from, p, plugin.kits().get(r.kit), null);
        if (err != null) {
            Msg.send(p, "duel.cannot-start", "{prefix}&c{reason}", "reason", err);
            Msg.send(from, "duel.cannot-start", "{prefix}&c{reason}", "reason", err);
        }
    }

    /** Startet ein Duell. Gibt eine Fehlermeldung oder null zurueck. Wird auch vom Turnier genutzt. */
    public String start(Player a, Player b, Kit kit, BiConsumer<Player, Player> onEnd) {
        if (kit == null) return "Kit nicht gefunden.";
        for (Player p : List.of(a, b)) {
            if (inDuel(p)) return p.getName() + " ist bereits in einem Duell.";
            if (plugin.combat().isTagged(p)) return p.getName() + " ist im Kampf.";
            if (p.isDead()) return p.getName() + " ist tot.";
        }
        String arena = freeArena();
        if (arena == null) return "Alle Arenen sind belegt. Versuche es gleich nochmal.";
        Location l1 = PlayerData.locFromString(plugin.getConfig().getString("duels.arenas." + arena + ".pos1"));
        Location l2 = PlayerData.locFromString(plugin.getConfig().getString("duels.arenas." + arena + ".pos2"));
        if (l1 == null || l2 == null) return "Arena " + arena + " ist nicht vollstaendig eingerichtet.";
        busyArenas.add(arena);
        Duel d = new Duel(a, b, kit, arena, onEnd);
        for (Player p : List.of(a, b)) {
            snapshot(p);
            duels.put(p.getUniqueId(), d);
            p.getInventory().clear();
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
            p.setFoodLevel(20);
            p.setSaturation(20);
            p.setFireTicks(0);
            p.getActivePotionEffects().forEach(pe -> p.removePotionEffect(pe.getType()));
            plugin.kits().equip(p, kit, true);
        }
        a.teleportAsync(l1);
        b.teleportAsync(l2);
        new org.bukkit.scheduler.BukkitRunnable() {
            int n = 3;
            @Override public void run() {
                if (d.phase != Phase.COUNTDOWN) { cancel(); return; }
                for (Player p : List.of(a, b)) {
                    p.showTitle(Title.title(Text.c(n > 0 ? "&b" + n : "&a&lKAMPF!"), Text.c("&7vs &f" + d.other(p).getName()),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200))));
                    p.playSound(p.getLocation(), n > 0 ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.ENTITY_ENDER_DRAGON_GROWL, 1, n > 0 ? 1 : 1.5f);
                }
                if (n-- == 0) { d.phase = Phase.FIGHT; cancel(); }
            }
        }.runTaskTimer(plugin, 10, 20);
        long max = plugin.getConfig().getLong("duels.max-minutes", 5) * 60 * 20;
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (d.phase != Phase.ENDED) end(d, null, null); }, max + 80);
        return null;
    }

    private String freeArena() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("duels.arenas");
        if (s == null) return null;
        for (String k : s.getKeys(false)) if (!busyArenas.contains(k)) return k;
        return null;
    }

    private void snapshot(Player p) {
        PlayerData d = plugin.data().get(p);
        d.savedInventory = PlayerData.locToString(p.getLocation()) + "|" + Items.arrayToBase64(p.getInventory().getContents())
                + "|" + p.getHealth() + "|" + p.getFoodLevel();
        plugin.data().save(d, p); // sofort sichern (Absturzschutz)
    }

    private void restore(Player p) {
        restore(p, false);
    }

    private void restore(Player p, boolean quitting) {
        PlayerData d = plugin.data().get(p);
        if (d.savedInventory == null || d.savedInventory.isEmpty()) return;
        String[] parts = d.savedInventory.split("\\|", -1);
        p.getInventory().clear();
        p.getInventory().setContents(Items.arrayFromBase64(parts.length > 1 ? parts[1] : "", p.getInventory().getSize()));
        try {
            double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
            if (parts.length > 2 && !p.isDead()) p.setHealth(Math.max(1, Math.min(max, Double.parseDouble(parts[2]))));
            if (parts.length > 3) p.setFoodLevel(Integer.parseInt(parts[3]));
        } catch (NumberFormatException ignored) {
        }
        Location back = PlayerData.locFromString(parts[0]);
        d.savedInventory = "";
        d.dirty = true;
        plugin.data().save(d, p);
        if (back != null && !p.isDead()) {
            if (quitting) p.teleport(back); else p.teleportAsync(back);
        }
    }

    private void end(Duel d, Player winner, Player loser) {
        if (d.phase == Phase.ENDED) return;
        d.phase = Phase.ENDED;
        duels.remove(d.a.getUniqueId());
        duels.remove(d.b.getUniqueId());
        busyArenas.remove(d.arena);
        if (winner != null) {
            PlayerData wd = plugin.data().get(winner), ld = plugin.data().get(loser);
            wd.duelWins++; ld.duelLosses++;
            wd.dirty = ld.dirty = true;
            Msg.broadcast("duel.result", "{prefix}&f{winner} &7hat das Duell gegen &f{loser} &7gewonnen! &8({hp}❤ uebrig)",
                    "winner", winner.getName(), "loser", loser.getName(), "hp", String.valueOf(Math.round(winner.getHealth()) / 2.0));
            plugin.quests().progress(winner, "DUEL_WIN", null, 1);
        } else {
            for (Player p : List.of(d.a, d.b)) Msg.send(p, "duel.draw", "{prefix}&7Das Duell endet unentschieden (Zeitlimit).");
        }
        for (Player p : List.of(d.a, d.b)) {
            if (!p.isOnline()) continue;
            if (p.isDead()) { restoreOnRespawn.add(p.getUniqueId()); continue; }
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && !inDuel(p)) restore(p); }, winner == null ? 1 : 60);
        }
        if (d.onEnd != null) d.onEnd.accept(winner, loser);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Duel d = duels.get(p.getUniqueId());
        if (d == null) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        e.deathMessage(null);
        p.getInventory().clear();
        end(d, d.other(p), p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!restoreOnRespawn.remove(p.getUniqueId())) return;
        PlayerData d = plugin.data().get(p);
        Location back = PlayerData.locFromString(d.savedInventory.split("\\|", -1)[0]);
        if (back != null) e.setRespawnLocation(back);
        Bukkit.getScheduler().runTask(plugin, () -> restore(p));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Duel d = duels.get(p.getUniqueId());
        if (d != null) {
            p.getInventory().clear();
            end(d, d.other(p), p);
        }
        restoreOnRespawn.remove(p.getUniqueId());
        if (!plugin.data().get(p).savedInventory.isEmpty()) restore(p, true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!plugin.data().get(p).savedInventory.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && !inDuel(p)) {
                    restore(p);
                    Msg.send(p, "duel.restored", "{prefix}&aDein Inventar aus einem unterbrochenen Duell wurde wiederhergestellt.");
                }
            }, 20);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Duel d = duels.get(e.getPlayer().getUniqueId());
        if (d != null && d.phase == Phase.COUNTDOWN && e.hasChangedPosition()
                && (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ())) {
            Location to = e.getFrom().clone();
            to.setYaw(e.getTo().getYaw());
            to.setPitch(e.getTo().getPitch());
            e.setTo(to);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (inDuel(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (inDuel(e.getPlayer()) && !e.getPlayer().hasPermission("minesmp.staff.commands")) {
            String m = e.getMessage().toLowerCase();
            if (m.startsWith("/msg ") || m.startsWith("/r ") || m.startsWith("/tell ")) return;
            e.setCancelled(true);
            Msg.send(e.getPlayer(), "duel.no-commands", "{prefix}&cWaehrend eines Duells sind Befehle gesperrt.");
        }
    }

    public void shutdown() {
        for (Duel d : new ArrayList<>(new HashSet<>(duels.values()))) {
            d.phase = Phase.ENDED;
            for (Player p : List.of(d.a, d.b)) if (p.isOnline() && !p.isDead()) restore(p);
        }
        duels.clear();
    }
}
