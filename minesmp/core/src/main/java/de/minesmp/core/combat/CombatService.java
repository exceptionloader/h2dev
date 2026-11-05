package de.minesmp.core.combat;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Combat-Tag: Nach PvP-Schaden X Sekunden im Kampf. Ausloggen = Tod. Befehle gesperrt. */
public final class CombatService implements Listener {
    private final MineSMP plugin;
    private final Map<UUID, Long> tagged = new HashMap<>();
    private final Set<String> blockedCommands = new HashSet<>();
    private final Set<String> pvpDisabledWorlds = new HashSet<>();
    private final Set<UUID> combatLogged = new HashSet<>();

    public CombatService(MineSMP plugin) {
        this.plugin = plugin;
        for (String c : plugin.getConfig().getStringList("combat.blocked-commands")) blockedCommands.add(c.toLowerCase(Locale.ROOT));
        pvpDisabledWorlds.addAll(plugin.getConfig().getStringList("combat.pvp-disabled-worlds"));
        plugin.listen(this);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10, 10);
    }

    private long duration() {
        return plugin.getConfig().getLong("combat.tag-seconds", 15) * 1000;
    }

    public boolean isTagged(Player p) {
        Long t = tagged.get(p.getUniqueId());
        return t != null && t > System.currentTimeMillis();
    }

    public long remaining(Player p) {
        Long t = tagged.get(p.getUniqueId());
        return t == null ? 0 : Math.max(0, t - System.currentTimeMillis());
    }

    public void tag(Player p) {
        if (p.hasPermission("minesmp.combat.bypass")) return;
        boolean was = isTagged(p);
        tagged.put(p.getUniqueId(), System.currentTimeMillis() + duration());
        if (!was) {
            Msg.send(p, "combat.tagged", "{prefix}&cDu bist im Kampf! &7Nicht ausloggen fuer &f{s}s&7.", "s", String.valueOf(duration() / 1000));
            if (p.isGliding() && plugin.getConfig().getBoolean("combat.disable-elytra", true)) p.setGliding(false);
        }
    }

    public void untag(Player p) {
        if (tagged.remove(p.getUniqueId()) != null) Msg.actionbar(p, "combat.untagged", "&aDu bist nicht mehr im Kampf.");
    }

    private void tick() {
        long now = System.currentTimeMillis();
        tagged.entrySet().removeIf(e -> {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) return true;
            if (e.getValue() <= now) {
                Msg.actionbar(p, "combat.untagged", "&aDu bist nicht mehr im Kampf.");
                return true;
            }
            long left = e.getValue() - now;
            p.sendActionBar(Text.c(Msg.get("combat.actionbar", "&c⚔ Im Kampf &8| &f{s}s &8| {bar}", "s", String.valueOf((left + 999) / 1000),
                    "bar", Text.progressBar(left / (double) duration(), 15, "&c", "&8"))));
            return false;
        });
    }

    public boolean isCombatLogging(Player p) {
        return combatLogged.contains(p.getUniqueId());
    }

    /** Mit WorldGuard regeln Regionen (pvp-Flag) die PvP-Zonen; diese Liste ist der Fallback ohne WorldGuard. */
    public boolean pvpAllowedIn(org.bukkit.World w) {
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) return true;
        return !pvpDisabledWorlds.contains(w.getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) attacker = p;
        if (attacker == null || attacker.equals(victim)) return;
        if (!pvpAllowedIn(victim.getWorld()) && !plugin.duels().inDuel(victim)) {
            e.setCancelled(true);
            return;
        }
        if (plugin.duels().inDuel(victim)) return;
        tag(attacker);
        tag(victim);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!isTagged(p)) return;
        String label = e.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
        if (blockedCommands.contains(label) || blockedCommands.contains("*")) {
            e.setCancelled(true);
            Msg.send(p, "combat.blocked", "{prefix}&cDas kannst du im Kampf nicht tun.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.isGliding() && e.getEntity() instanceof Player p && isTagged(p) && plugin.getConfig().getBoolean("combat.disable-elytra", true)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!isTagged(p)) return;
        tagged.remove(p.getUniqueId());
        if (e.getReason() == PlayerQuitEvent.QuitReason.KICKED && !plugin.getConfig().getBoolean("combat.punish-kicks", false)) return;
        if (Bukkit.isStopping()) return;
        combatLogged.add(p.getUniqueId());
        p.setHealth(0); // Items droppen wie bei normalem Tod
        combatLogged.remove(p.getUniqueId());
        Msg.broadcast("combat.logged", "{prefix}&c{name} &7hat sich im Kampf ausgeloggt und ist gestorben.", "name", p.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        tagged.remove(e.getEntity().getUniqueId());
    }
}
