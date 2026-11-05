package de.minesmp.core.display;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Text;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sidebar-Scoreboard, animierte Tablist (Header/Footer), Tab-Namen mit Rang/Clan,
 * Nametags + Tab-Sortierung ueber Teams, Bossbars fuer Events/Kriege.
 */
public final class ScoreboardService implements Listener {
    private final MineSMP plugin;
    private YamlConfiguration cfg;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, Map<String, BossBar>> bars = new HashMap<>();
    private int frame, titleFrame;

    public ScoreboardService(MineSMP plugin) {
        this.plugin = plugin;
        reload();
        plugin.listen(this);
        for (Player p : Bukkit.getOnlinePlayers()) setup(p);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            frame++;
            for (Player p : Bukkit.getOnlinePlayers()) update(p);
        }, 20, Math.max(5, cfg.getLong("update-ticks", 20)));
        // Titel-Animation laeuft schneller als der Rest (nur der Titel wird neu gesendet)
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            var g = cfg.getConfigurationSection("title-gradient");
            if (g == null || !g.getBoolean("enabled", true)) return;
            titleFrame++;
            var t = Text.c(title());
            for (Player p : Bukkit.getOnlinePlayers()) {
                Scoreboard sb = boards.get(p.getUniqueId());
                Objective o = sb == null ? null : sb.getObjective("minesmp");
                if (o != null && o.getDisplaySlot() == DisplaySlot.SIDEBAR) o.displayName(t);
            }
        }, 20, 3);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshNametags, 100, 200);
    }

    public void reload() {
        cfg = plugin.yaml("scoreboard.yml");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        setup(e.getPlayer());
        refreshNametags();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
        Map<String, BossBar> b = bars.remove(e.getPlayer().getUniqueId());
        if (b != null) b.values().forEach(bar -> e.getPlayer().hideBossBar(bar));
        Bukkit.getScheduler().runTask(plugin, this::refreshNametags);
    }

    private void setup(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o = sb.registerNewObjective("minesmp", Criteria.DUMMY, Text.c(cfg.getString("title.0", "&b&lMINE&f&lSMP")));
        o.numberFormat(NumberFormat.blank());
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        p.setScoreboard(sb);
        boards.put(p.getUniqueId(), sb);
        update(p);
    }

    /** Titel: animierter Farbverlauf (title-gradient) oder klassische Einzelbilder (title-frames). */
    private String title() {
        var g = cfg.getConfigurationSection("title-gradient");
        if (g != null && g.getBoolean("enabled", true)) {
            List<String> colors = g.getStringList("colors");
            if (colors.size() < 2) colors = List.of("#55FFFF", "#FFFFFF");
            double speed = g.getDouble("speed", 0.04);
            return Text.gradient(g.getString("text", "MINESMP"), (titleFrame * speed) % 1.0 + 1e-9, g.getBoolean("bold", true) ? "&l" : "", colors.toArray(new String[0]));
        }
        return animated("title-frames", "&b&lMINE&f&lSMP");
    }

    private String animated(String path, String def) {
        List<String> frames = cfg.getStringList(path);
        if (frames.isEmpty()) return cfg.getString(path, def);
        return frames.get(frame % frames.size());
    }

    public void update(Player p) {
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null) return;
        Objective o = sb.getObjective("minesmp");
        if (o == null) return;
        boolean show = plugin.data().get(p).setting("scoreboard", true);
        if (!show) {
            o.setDisplaySlot(null);
        } else {
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            o.displayName(Text.c(title()));
            List<String> lines = plugin.combat().isTagged(p) && !cfg.getStringList("combat-lines").isEmpty() ? cfg.getStringList("combat-lines") : cfg.getStringList("lines");
            int n = Math.min(15, lines.size());
            for (int i = 0; i < 15; i++) {
                String entry = "l" + i;
                if (i >= n) { sb.resetScores(entry); continue; }
                var score = o.getScore(entry);
                score.setScore(n - i);
                String line = plugin.placeholders().apply(p, lines.get(i));
                int split = line.indexOf("<r>");
                if (split >= 0) {
                    score.customName(Text.c(line.substring(0, split)));
                    score.numberFormat(NumberFormat.fixed(Text.c(line.substring(split + 3))));
                } else {
                    score.customName(Text.c(line));
                    score.numberFormat(null);
                }
            }
        }
        p.sendPlayerListHeaderAndFooter(
                Text.c(plugin.placeholders().apply(p, String.join("\n", cfg.getStringList("tab.header-frames." + (frame % Math.max(1, cfg.getInt("tab.frame-count", 1))))))),
                Text.c(plugin.placeholders().apply(p, String.join("\n", cfg.getStringList("tab.footer")))));
        refreshTabName(p);
    }

    public void refreshTabName(Player p) {
        String fmt = cfg.getString("tab.name-format", "{prefix}{clan}&f{name}{afk}");
        p.playerListName(Text.c(Text.replace(fmt, "prefix", plugin.chat().prefix(p), "clan", plugin.clans().tag(p), "name", p.getName(),
                "afk", plugin.afk().isAfk(p) ? " &7[AFK]" : "")));
    }

    /** Teams fuer Nametags und Tab-Sortierung (nach Rang-Reihenfolge in scoreboard.yml -> sort-groups). */
    public void refreshNametags() {
        List<String> order = cfg.getStringList("sort-groups");
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard sb = boards.get(viewer.getUniqueId());
            if (sb == null) continue;
            for (Team t : sb.getTeams()) {
                boolean stillOnline = false;
                for (String e : t.getEntries()) if (Bukkit.getPlayerExact(e) != null) { stillOnline = true; break; }
                if (!stillOnline) t.unregister();
            }
            for (Player target : Bukkit.getOnlinePlayers()) {
                int idx = order.size();
                for (int i = 0; i < order.size(); i++) if (target.hasPermission("group." + order.get(i))) { idx = i; break; }
                String teamName = String.format("%03d", idx) + target.getName();
                if (teamName.length() > 64) teamName = teamName.substring(0, 64);
                Team old = sb.getEntryTeam(target.getName());
                if (old != null && !old.getName().equals(teamName)) old.unregister();
                Team t = sb.getTeam(teamName);
                if (t == null) t = sb.registerNewTeam(teamName);
                t.prefix(Text.c(plugin.chat().prefix(target) + plugin.clans().tag(target)));
                if (!t.hasEntry(target.getName())) t.addEntry(target.getName());
            }
        }
    }

    // ---------------- Bossbars ----------------

    public void bossbar(Player p, String id, String text, double progress, BarColor color) {
        Map<String, BossBar> m = bars.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        BossBar.Color c = switch (color) {
            case RED -> BossBar.Color.RED; case BLUE -> BossBar.Color.BLUE; case GREEN -> BossBar.Color.GREEN;
            case YELLOW -> BossBar.Color.YELLOW; case PURPLE -> BossBar.Color.PURPLE; case PINK -> BossBar.Color.PINK; default -> BossBar.Color.WHITE;
        };
        float prog = (float) Math.max(0, Math.min(1, progress));
        BossBar bar = m.get(id);
        if (bar == null) {
            bar = BossBar.bossBar(Text.c(text), prog, c, BossBar.Overlay.PROGRESS);
            m.put(id, bar);
            p.showBossBar(bar);
        } else {
            bar.name(Text.c(text));
            bar.progress(prog);
            bar.color(c);
        }
    }

    public boolean hasBossbar(Player p, String id) {
        Map<String, BossBar> m = bars.get(p.getUniqueId());
        return m != null && m.containsKey(id);
    }

    public void hideBossbar(Player p, String id) {
        Map<String, BossBar> m = bars.get(p.getUniqueId());
        if (m == null) return;
        BossBar bar = m.remove(id);
        if (bar != null) p.hideBossBar(bar);
    }
}
