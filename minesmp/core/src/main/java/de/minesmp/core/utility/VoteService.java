package de.minesmp.core.utility;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * /vote mit Links und Vote-Belohnungen ueber NuVotifier (optional, per Reflection - keine harte Abhaengigkeit).
 * Vote-Party: Nach X Votes bekommen alle Online-Spieler eine Belohnung.
 */
public final class VoteService implements Listener {
    private final MineSMP plugin;
    private int partyCount;

    @SuppressWarnings("unchecked")
    public VoteService(MineSMP plugin) {
        this.plugin = plugin;
        try {
            String v = plugin.db().kvGet("voteparty").get(5, TimeUnit.SECONDS);
            partyCount = v == null ? 0 : Integer.parseInt(v);
        } catch (Exception ignored) {
        }
        plugin.command(new SimpleCommand("vote", "minesmp.vote", "Fuer MineSMP voten", (s, l, a) -> {
            Msg.send(s, "vote.header", "&8&m-----&r &a&lVoten &8&m-----\n&7Vote taeglich und erhalte &eVote-Schluessel&7!\n&7Vote-Party: &f{n}&7/&f{max}",
                    "n", String.valueOf(partyCount), "max", String.valueOf(plugin.getConfig().getInt("vote.party-votes", 50)));
            int i = 1;
            for (String url : plugin.getConfig().getStringList("vote.links")) {
                s.sendMessage(Text.c("&a" + (i++) + ". &b&n" + url).clickEvent(ClickEvent.openUrl(url)));
            }
        }, "voten", "votes"));
        if (Bukkit.getPluginManager().getPlugin("Votifier") != null) {
            try {
                Class<? extends Event> evt = (Class<? extends Event>) Class.forName("com.vexsoftware.votifier.model.VotifierEvent");
                Bukkit.getPluginManager().registerEvent(evt, this, EventPriority.NORMAL, (l, e) -> {
                    if (!evt.isInstance(e)) return;
                    try {
                        Object vote = e.getClass().getMethod("getVote").invoke(e);
                        Method user = vote.getClass().getMethod("getUsername");
                        Method service = vote.getClass().getMethod("getServiceName");
                        String name = (String) user.invoke(vote), svc = (String) service.invoke(vote);
                        plugin.sync(() -> onVote(name, svc));
                    } catch (ReflectiveOperationException ex) {
                        plugin.getLogger().warning("Vote konnte nicht gelesen werden: " + ex.getMessage());
                    }
                }, plugin);
                plugin.getLogger().info("NuVotifier gefunden - Vote-Belohnungen aktiv.");
            } catch (ClassNotFoundException ex) {
                plugin.getLogger().warning("Votifier gefunden, aber VotifierEvent-Klasse fehlt.");
            }
        }
        plugin.command(new SimpleCommand("fakevote", "minesmp.admin.vote", "Test-Vote ausloesen", (s, l, a) -> {
            if (a.length > 0) { onVote(a[0], "Test"); plugin.audit().log(s, "FAKEVOTE", a[0]); }
        }));
    }

    public void onVote(String name, String service) {
        if (name == null || !name.matches("[A-Za-z0-9_.]{2,17}")) return;
        Player p = Bukkit.getPlayerExact(name);
        ConfigurationSection r = plugin.getConfig().getConfigurationSection("vote.reward");
        if (p != null) {
            plugin.rewards().give(p, r, "Vote");
            plugin.data().get(p).addStat("votes", 1);
        } else {
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
            if (op == null || r == null) return;
            ConfigurationSection keys = r.getConfigurationSection("keys");
            if (keys != null) for (String k : keys.getKeys(false)) plugin.crates().giveKeys(op.getUniqueId(), k, keys.getInt(k));
            if (r.getDouble("money") > 0) plugin.eco().depositAny(op.getUniqueId(), r.getDouble("money"), "VOTE", service);
        }
        Msg.broadcast("vote.broadcast", "{prefix}&f{name} &7hat auf &a{service} &7gevotet! &8(&f/vote&8)", "name", name, "service", service == null ? "?" : service);
        partyCount++;
        int need = plugin.getConfig().getInt("vote.party-votes", 50);
        if (partyCount >= need) {
            partyCount = 0;
            Msg.broadcast("vote.party", "{prefix}&d&lVOTE-PARTY! &7Alle Online-Spieler erhalten eine Belohnung!");
            ConfigurationSection pr = plugin.getConfig().getConfigurationSection("vote.party-reward");
            for (Player o : Bukkit.getOnlinePlayers()) plugin.rewards().give(o, pr, "Vote-Party");
        }
        plugin.db().kvSet("voteparty", String.valueOf(partyCount));
    }
}
