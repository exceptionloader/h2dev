package de.minesmp.core.utility;

import de.minesmp.core.MineSMP;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mitspieler-Suche (/mitspieler <Text>): Spieler posten, wofuer sie Leute suchen (Base bauen, Farmen, PvP ...).
 * Andere klicken im Chat oder im Menue auf "Mitmachen" und schicken damit eine Anfrage.
 */
public final class LfgService implements Listener {
    private record Post(UUID owner, String name, String text, long created) {}

    private final MineSMP plugin;
    private final Map<UUID, Post> posts = new LinkedHashMap<>();

    public LfgService(MineSMP plugin) {
        this.plugin = plugin;
        plugin.listen(this);
        plugin.command(new SimpleCommand("mitspieler", "minesmp.lfg", "Mitspieler suchen", (s, l, a) -> {
            if (!(s instanceof Player p)) return;
            if (a.length == 0) { open(p); return; }
            if (a[0].equalsIgnoreCase("stop")) { posts.remove(p.getUniqueId()); Msg.send(p, "lfg.stopped", "{prefix}&7Deine Suche wurde beendet."); return; }
            if (a[0].equalsIgnoreCase("join") && a.length >= 2) { join(p, Bukkit.getPlayerExact(a[1])); return; }
            String text = Text.strip(String.join(" ", a));
            if (text.length() > 80) text = text.substring(0, 80);
            if (!plugin.cooldowns().tryUse(p.getUniqueId(), "lfg", plugin.getConfig().getInt("lfg.cooldown-seconds", 300) * 1000L)) {
                Msg.send(p, "cooldown", "{prefix}&cBitte warte noch &f{time}&c.", "time", TimeUtil.format(plugin.cooldowns().remaining(p.getUniqueId(), "lfg")));
                return;
            }
            posts.put(p.getUniqueId(), new Post(p.getUniqueId(), p.getName(), text, System.currentTimeMillis()));
            Component join = Text.c(" &a&l[MITMACHEN]").clickEvent(ClickEvent.runCommand("/mitspieler join " + p.getName()))
                    .hoverEvent(HoverEvent.showText(Text.c("&7Klicke, um &f" + p.getName() + " &7eine Anfrage zu schicken")));
            Component msg = Text.c(Msg.get("lfg.broadcast", "{prefix}&b{name} &7sucht Mitspieler: &f{text}", "name", p.getName(), "text", text)).append(join);
            for (Player o : Bukkit.getOnlinePlayers()) o.sendMessage(msg);
        }, (s, a) -> a.length == 1 ? List.of("stop", "<Text>") : List.of(), "lfg", "suche"));
    }

    private long maxAge() { return plugin.getConfig().getInt("lfg.minutes", 20) * 60_000L; }

    private void clean() {
        long now = System.currentTimeMillis();
        posts.values().removeIf(p -> now - p.created > maxAge() || Bukkit.getPlayer(p.owner) == null);
    }

    private void join(Player p, Player owner) {
        clean();
        if (owner == null || owner == p || !posts.containsKey(owner.getUniqueId())) { Msg.send(p, "lfg.gone", "{prefix}&cDiese Suche ist nicht mehr aktiv."); return; }
        if (!plugin.cooldowns().tryUse(p.getUniqueId(), "lfg-join:" + owner.getUniqueId(), 60_000)) return;
        Component tp = Text.c(" &a&l[ZU MIR HOLEN]").clickEvent(ClickEvent.runCommand("/tpahere " + p.getName()))
                .hoverEvent(HoverEvent.showText(Text.c("&7Schickt &f" + p.getName() + " &7eine Teleport-Anfrage zu dir")));
        Component msg = Component.text(" ").append(Text.c("&b&lAntwort ")).append(Text.c(Msg.get("lfg.request", "{prefix}&f{name} &7will bei deiner Suche mitmachen!", "name", p.getName()))).append(tp);
        owner.sendMessage(msg);
        owner.playSound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1, 1.5f);
        Msg.send(p, "lfg.sent", "{prefix}&aAnfrage an &f{name} &agesendet.", "name", owner.getName());
    }

    private void open(Player p) {
        clean();
        Menu m = new Menu(4, "&8Mitspieler-Suche") {};
        int slot = 0;
        for (Post post : new ArrayList<>(posts.values())) {
            if (slot >= 27) break;
            Player owner = Bukkit.getPlayer(post.owner);
            if (owner == null) continue;
            m.set(slot++, Items.head(owner, "&b" + post.name, List.of("&f" + post.text, "&7vor &f" + TimeUtil.format(System.currentTimeMillis() - post.created),
                    "&8", post.owner.equals(p.getUniqueId()) ? "&7Das ist deine Suche" : "&a▶ Klicke zum Mitmachen")), e -> {
                Menu.close(p);
                if (!post.owner.equals(p.getUniqueId())) join(p, owner);
            });
        }
        m.set(31, Items.of(org.bukkit.Material.WRITABLE_BOOK, 1, "&a&lSelbst suchen", List.of("&b/mitspieler <Text>", "&7z.B. &f/mitspieler Suche Leute fuer eine Stadt", "&7Beenden: &f/mitspieler stop")));
        m.fillEmpty();
        m.open(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        posts.remove(e.getPlayer().getUniqueId());
    }
}
