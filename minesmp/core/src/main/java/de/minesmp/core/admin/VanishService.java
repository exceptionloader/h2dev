package de.minesmp.core.admin;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Zwei Vanish-Modi fuer das Team:
 * /vanish       - komplett unsichtbar: Spieler sehen dich nicht und du selbst siehst deinen Skin auch nicht.
 * /vanishultra  - Spieler sehen dich nicht, du selbst siehst deinen Skin ganz normal.
 * In beiden Modi kann man alles tun (bauen, Kisten oeffnen, kaempfen). Teammitglieder
 * (minesmp.staff.member oder minesmp.staff.vanish) sehen andere Teammitglieder immer:
 * im /vanish als durchsichtige Figur, im /vanishultra ganz normal. Namensschild und Tab-Eintrag bleiben unveraendert.
 */
public final class VanishService implements Listener {
    public enum Mode {
        FULL("Vanish", "&b"), ULTRA("Vanish Ultra", "&d");
        public final String display, color;
        Mode(String display, String color) { this.display = display; this.color = color; }
    }

    private final MineSMP plugin;
    private final Map<UUID, Mode> vanished = new HashMap<>();

    public VanishService(MineSMP plugin) {
        this.plugin = plugin;
        plugin.listen(this);
        plugin.command(new SimpleCommand("vanish", "minesmp.staff.vanish", "Komplett unsichtbar (auch dein eigener Skin)",
                (s, l, a) -> cmd(s, a, Mode.FULL), (s, a) -> a.length == 1 ? List.of("list") : List.of(), "v"));
        plugin.command(new SimpleCommand("vanishultra", "minesmp.staff.vanish", "Unsichtbar fuer Spieler, du siehst deinen Skin",
                (s, l, a) -> cmd(s, a, Mode.ULTRA), (s, a) -> a.length == 1 ? List.of("list") : List.of(), "vu", "ultravanish"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40, 40);
    }

    public Mode mode(Player p) { return vanished.get(p.getUniqueId()); }

    public boolean isVanished(Player p) { return vanished.containsKey(p.getUniqueId()); }

    public int count() { return vanished.size(); }

    /** Teammitglieder sehen Spieler im Vanish. */
    public boolean sees(Player viewer) {
        return viewer.hasPermission("minesmp.staff.member") || viewer.hasPermission("minesmp.staff.vanish");
    }

    private void cmd(CommandSender s, String[] a, Mode mode) {
        if (a.length > 0 && a[0].equalsIgnoreCase("list")) {
            List<String> names = new ArrayList<>();
            for (Map.Entry<UUID, Mode> en : vanished.entrySet()) {
                Player v = Bukkit.getPlayer(en.getKey());
                if (v != null) names.add(en.getValue().color + v.getName() + " &8(" + en.getValue().display + ")");
            }
            Msg.send(s, "vanish.list", "{prefix}&7Im Vanish: &f{list}", "list", names.isEmpty() ? "niemand" : String.join("&7, ", names));
            return;
        }
        if (!(s instanceof Player p)) { Msg.send(s, "only-players", "{prefix}&cDieser Befehl ist nur fuer Spieler."); return; }
        if (vanished.get(p.getUniqueId()) == mode) disable(p, true);
        else enable(p, mode, true);
    }

    private void remember(Player p, Mode mode) {
        PlayerData d = plugin.data().get(p);
        d.settings.put("vanish", mode == Mode.FULL);
        d.settings.put("vanish-ultra", mode == Mode.ULTRA);
        d.dirty = true;
    }

    private void applyState(Player p, Mode mode) {
        setInvisible(p, mode == Mode.FULL);
        p.setSleepingIgnored(true);
        refreshVisibility(p);
        for (Entity e : p.getNearbyEntities(48, 48, 48)) if (e instanceof Mob m && p.equals(m.getTarget())) m.setTarget(null);
    }

    private static void setInvisible(Player p, boolean invisible) {
        p.setInvisible(invisible);
        // Echten Unsichtbarkeitstrank nach dem Ausschalten nicht verlieren
        if (!invisible && p.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            PotionEffect eff = p.getPotionEffect(PotionEffectType.INVISIBILITY);
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
            if (eff != null) p.addPotionEffect(eff);
        }
    }

    public void enable(Player p, Mode mode, boolean announce) {
        Mode old = vanished.put(p.getUniqueId(), mode);
        remember(p, mode);
        applyState(p, mode);
        showUi(p, mode);
        if (announce) {
            if (mode == Mode.FULL) Msg.send(p, "vanish.on", "{prefix}&b&lVanish an. &7Spieler sehen dich nicht und du siehst deinen eigenen Skin nicht. Das Team sieht dich weiter.");
            else Msg.send(p, "vanish.ultra-on", "{prefix}&d&lVanish Ultra an. &7Spieler sehen dich nicht, du selbst siehst deinen Skin. Das Team sieht dich weiter.");
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.equals(p)) continue;
                if (sees(o)) Msg.send(o, "vanish.team-on", "&8[&cTeam&8] &b{name} &7ist jetzt im &f{mode}&7.", "name", p.getName(), "mode", mode.display);
                else if (old == null && plugin.getConfig().getBoolean("vanish.fake-messages", true) && plugin.data().get(o).setting("join-messages", true))
                    o.sendMessage(Text.c(Msg.get("quit.message", "&8[&c-&8] &7{name}", "name", p.getName())));
            }
            plugin.audit().log(p, "VANISH", mode.display + " an");
        }
    }

    public void disable(Player p, boolean announce) {
        if (vanished.remove(p.getUniqueId()) == null) return;
        remember(p, null);
        setInvisible(p, false);
        p.setSleepingIgnored(false);
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) o.showPlayer(plugin, p);
        plugin.scoreboard().hideBossbar(p, "vanish");
        plugin.scoreboard().refreshTabName(p);
        if (plugin.cosmetics() != null) plugin.cosmetics().refresh(p);
        if (announce) {
            Msg.send(p, "vanish.off", "{prefix}&7Du bist wieder &fsichtbar&7.");
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.equals(p)) continue;
                if (sees(o)) Msg.send(o, "vanish.team-off", "&8[&cTeam&8] &b{name} &7ist wieder sichtbar.", "name", p.getName());
                else if (plugin.getConfig().getBoolean("vanish.fake-messages", true) && plugin.data().get(o).setting("join-messages", true))
                    o.sendMessage(Text.c(Msg.get("join.message", "&8[&a+&8] &7{name}", "name", p.getName())));
            }
            plugin.audit().log(p, "VANISH", "aus");
        }
    }

    private void showUi(Player p, Mode mode) {
        plugin.scoreboard().bossbar(p, "vanish", mode == Mode.FULL
                ? Msg.get("vanish.bar", "&b&lVANISH &8┃ &7Unsichtbar - auch fuer dich selbst &8┃ &f/vanish")
                : Msg.get("vanish.bar-ultra", "&d&lVANISH ULTRA &8┃ &7Unsichtbar fuer Spieler &8┃ &f/vanishultra"), 1.0, mode == Mode.FULL ? BarColor.BLUE : BarColor.PURPLE);
        plugin.scoreboard().refreshTabName(p);
        if (plugin.cosmetics() != null) plugin.cosmetics().refresh(p);
    }

    private void refreshVisibility(Player p) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p)) continue;
            if (sees(o)) o.showPlayer(plugin, p);
            else o.hidePlayer(plugin, p);
        }
    }

    private void tick() {
        for (Map.Entry<UUID, Mode> en : new ArrayList<>(vanished.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) continue;
            if (!p.hasPermission("minesmp.staff.vanish")) { disable(p, true); continue; }
            refreshVisibility(p);
            if (en.getValue() == Mode.FULL && !p.isInvisible() && !p.isDead()) p.setInvisible(true);
            plugin.scoreboard().updateVanishListName(p);
        }
    }

    // ---------------- Events ----------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.data().get(p);
        boolean full = d.setting("vanish", false), ultra = d.setting("vanish-ultra", false);
        if ((full || ultra) && p.hasPermission("minesmp.staff.vanish") && plugin.getConfig().getBoolean("vanish.remember", true)) {
            Mode mode = ultra ? Mode.ULTRA : Mode.FULL;
            vanished.put(p.getUniqueId(), mode);
            applyState(p, mode);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || !isVanished(p)) return;
                showUi(p, mode);
                Msg.send(p, "vanish.still", "{prefix}&7Du bist noch im &f{mode}&7.", "mode", mode.display);
            }, 20);
        } else if (full || ultra) {
            remember(p, null);
            if (full) setInvisible(p, false);
        }
        if (!sees(p)) {
            for (UUID id : vanished.keySet()) {
                Player v = Bukkit.getPlayer(id);
                if (v != null && !v.equals(p)) p.hidePlayer(plugin, v);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Mode m = vanished.remove(p.getUniqueId());
        if (m == null) return;
        if (m == Mode.FULL) p.setInvisible(false);
        p.setSleepingIgnored(false);
        plugin.scoreboard().hideBossbar(p, "vanish");
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (mode(p) == Mode.FULL) Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && mode(p) == Mode.FULL) p.setInvisible(true); });
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && isVanished(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && isVanished(p) && !plugin.getConfig().getBoolean("vanish.pickup-items", true)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        if (isVanished(e.getEntity())) e.deathMessage(null);
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (isVanished(e.getPlayer())) e.message(null);
    }
}
