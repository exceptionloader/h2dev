package de.minesmp.core.utility;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** AFK-Erkennung: automatisch nach Inaktivitaet, /afk manuell, optionaler Kick. */
public final class AfkService implements Listener {
    private final MineSMP plugin;
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();

    public AfkService(MineSMP plugin) {
        this.plugin = plugin;
        plugin.listen(this);
        plugin.command(new SimpleCommand("afk", "minesmp.afk", "AFK-Status", (s, l, a) -> {
            if (s instanceof Player p) setAfk(p, !isAfk(p));
        }));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200, 200);
    }

    public boolean isAfk(Player p) {
        return afk.contains(p.getUniqueId());
    }

    public void setAfk(Player p, boolean value) {
        if (value == isAfk(p)) return;
        if (value) {
            afk.add(p.getUniqueId());
            Msg.broadcast("afk.on", "&8» &7{name} ist jetzt &eAFK&7.", "name", p.getName());
        } else {
            afk.remove(p.getUniqueId());
            lastActive.put(p.getUniqueId(), System.currentTimeMillis());
            Msg.broadcast("afk.off", "&8» &7{name} ist wieder &ada&7.", "name", p.getName());
        }
        plugin.scoreboard().refreshTabName(p);
    }

    private void active(Player p) {
        lastActive.put(p.getUniqueId(), System.currentTimeMillis());
        if (isAfk(p)) Bukkit.getScheduler().runTask(plugin, () -> setAfk(p, false));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long afkAfter = plugin.getConfig().getLong("afk.auto-minutes", 5) * 60_000;
        long kickAfter = plugin.getConfig().getLong("afk.kick-minutes", 30) * 60_000;
        for (Player p : Bukkit.getOnlinePlayers()) {
            long last = lastActive.computeIfAbsent(p.getUniqueId(), k -> now);
            if (!isAfk(p) && now - last > afkAfter) setAfk(p, true);
            if (kickAfter > 0 && now - last > kickAfter && !p.hasPermission("minesmp.afk.nokick")
                    && Bukkit.getOnlinePlayers().size() >= plugin.getConfig().getInt("afk.kick-min-online", 30)) {
                p.kick(Text.c(Msg.get("afk.kick", "&b&lMine&f&lSMP\n\n&7Du warst zu lange AFK.")));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.hasChangedOrientation() || e.hasChangedBlock()) {
            if (e.getPlayer().isInsideVehicle()) return; // AFK-Pools/Minecarts zaehlen nicht
            active(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!e.getMessage().toLowerCase().startsWith("/afk")) active(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) { active(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        afk.remove(e.getPlayer().getUniqueId());
        lastActive.remove(e.getPlayer().getUniqueId());
    }
}
