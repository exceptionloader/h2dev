package de.minesmp.core.utility;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.Text;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

/** Premium: Spieler mit minesmp.joinfull kommen auch auf den vollen Server. */
public final class PremiumListener implements Listener {
    private final MineSMP plugin;

    public PremiumListener(MineSMP plugin) {
        this.plugin = plugin;
        plugin.listen(this);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent e) {
        if (e.getResult() != PlayerLoginEvent.Result.KICK_FULL) return;
        if (e.getPlayer().hasPermission("minesmp.joinfull")) {
            e.allow();
            return;
        }
        e.kickMessage(Text.c(Msg.get("premium.full", "&cDer Server ist voll!\n&7Mit &6&lPremium &7kommst du immer rein: &f{store}",
                "store", plugin.getConfig().getString("links.store", ""))));
    }
}
