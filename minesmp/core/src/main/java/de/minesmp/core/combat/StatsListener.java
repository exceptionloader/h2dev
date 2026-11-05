package de.minesmp.core.combat;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.Text;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Kills, Tode, Killstreaks, Todesnachrichten, Kill-Belohnung mit Farm-Schutz, optionales Lifesteal. */
public final class StatsListener implements Listener {
    private final MineSMP plugin;
    private final Map<String, Long> lastKill = new HashMap<>();
    private final NamespacedKey heartsKey;

    public StatsListener(MineSMP plugin) {
        this.plugin = plugin;
        this.heartsKey = new NamespacedKey(plugin, "lifesteal");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        if (plugin.duels().inDuel(victim)) return; // Duelle zaehlen separat
        PlayerData vd = plugin.data().get(victim);
        vd.deaths++;
        if (vd.killstreak >= 5) {
            Msg.broadcast("streak.ended", "{prefix}&7Die Killstreak von &c{name} &7({n}) wurde beendet!", "name", victim.getName(), "n", String.valueOf(vd.killstreak));
        }
        vd.killstreak = 0;
        vd.dirty = true;

        Player killer = victim.getKiller();
        if (plugin.combat().isCombatLogging(victim)) e.deathMessage(null); // eigene Nachricht in CombatService
        else if (killer == null || killer.equals(victim)) {
            e.deathMessage(Text.c(Msg.get("death." + cause(victim), "&8☠ &7{name} ist gestorben.", "name", victim.getName())));
            return;
        }
        if (killer == null || killer.equals(victim)) return;
        ItemStack weapon = killer.getInventory().getItemInMainHand();
        String weaponName = weapon.getType().isAir() ? "der Faust" : Items.name(weapon);
        if (!plugin.combat().isCombatLogging(victim)) e.deathMessage(Text.c(Msg.get("death.pvp", "&8⚔ &c{victim} &7wurde von &c{killer} &7mit &f{weapon} &7getoetet.",
                "victim", victim.getName(), "killer", killer.getName(), "weapon", weaponName)));

        // Kill-Farming-Schutz: gleicher Gegner innerhalb X Minuten oder gleiche IP -> keine Belohnung
        String key = killer.getUniqueId() + ">" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("combat.same-victim-cooldown-minutes", 10) * 60_000;
        boolean farm = now - lastKill.getOrDefault(key, 0L) < cooldown;
        if (killer.getAddress() != null && victim.getAddress() != null && killer.getAddress().getAddress().equals(victim.getAddress().getAddress())) farm = true;
        lastKill.put(key, now);
        if (lastKill.size() > 5000) lastKill.entrySet().removeIf(x -> now - x.getValue() > cooldown);
        if (farm) {
            Msg.send(killer, "kill.farm", "{prefix}&7Diesen Kill gibt es gerade keine Belohnung (Kill-Farming-Schutz).");
            return;
        }
        PlayerData kd = plugin.data().get(killer);
        kd.kills++;
        kd.killstreak++;
        kd.bestStreak = Math.max(kd.bestStreak, kd.killstreak);
        kd.dirty = true;
        double reward = plugin.getConfig().getDouble("combat.kill-reward", 50) + plugin.getConfig().getDouble("combat.streak-bonus", 10) * Math.min(kd.killstreak, 20);
        if (reward > 0) plugin.eco().deposit(killer, reward, "KILL", victim.getName());
        Msg.send(killer, "kill.reward", "{prefix}&7Kill: &c{victim} &8| &a+{money} &8| &7Streak: &c{streak}",
                "victim", victim.getName(), "money", plugin.eco().format(reward), "streak", String.valueOf(kd.killstreak));
        List<Integer> announce = plugin.getConfig().getIntegerList("combat.streak-announce");
        if (announce.contains(kd.killstreak)) {
            Msg.broadcast("streak.reached", "{prefix}&c&l{name} &7hat eine Killstreak von &c&l{n}&7!", "name", killer.getName(), "n", String.valueOf(kd.killstreak));
        }
        plugin.bounties().claim(killer, victim);
        plugin.clans().onKill(killer, victim);
        plugin.quests().progress(killer, "KILL_PLAYER", null, 1);
        plugin.skills().addXp(killer, "combat", plugin.getConfig().getDouble("combat.player-kill-xp", 25));

        if (plugin.getConfig().getBoolean("lifesteal.enabled", false)) {
            int max = plugin.getConfig().getInt("lifesteal.max-extra-hearts", 10), min = plugin.getConfig().getInt("lifesteal.min-extra-hearts", -5);
            if (vd.extraHearts > min) { vd.extraHearts--; applyHearts(victim, vd); }
            if (kd.extraHearts < max) { kd.extraHearts++; applyHearts(killer, kd); }
            Msg.send(killer, "lifesteal.gain", "{prefix}&c+1 ❤ &7(gestohlen von {name})", "name", victim.getName());
        }
    }

    private String cause(Player p) {
        EntityDamageEvent last = p.getLastDamageCause();
        return last == null ? "generic" : last.getCause().name().toLowerCase();
    }

    public void applyHearts(Player p, PlayerData d) {
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;
        attr.removeModifier(heartsKey);
        if (d.extraHearts != 0 && plugin.getConfig().getBoolean("lifesteal.enabled", false)) {
            attr.addModifier(new AttributeModifier(heartsKey, d.extraHearts * 2.0, AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applyHearts(e.getPlayer(), plugin.data().get(e.getPlayer()));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        applyHearts(e.getPlayer(), plugin.data().get(e.getPlayer()));
    }
}
