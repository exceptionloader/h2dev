package de.minesmp.core.display;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Info-Bossbar oben (wechselnde Infos, die Leiste laeuft pro Eintrag ab) und
 * dauerhafte Actionbar ueber der Hotbar. Konfiguration: scoreboard.yml -> infobar / actionbar.
 * Event- und Kriegs-Bossbars haben Vorrang; andere Actionbar-Meldungen (Kampf, Teleport, Einnahmen) ebenso.
 */
public final class InfoBarService {
    private record Entry(String text, int seconds, BarColor color) {}

    private final MineSMP plugin;
    private final List<Entry> entries = new ArrayList<>();
    private boolean barEnabled, abEnabled;
    private String abText = "";
    private long cycleStart = System.currentTimeMillis();
    private int index;

    public InfoBarService(MineSMP plugin) {
        this.plugin = plugin;
        reload();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40, 10);
    }

    public void reload() {
        YamlConfiguration y = plugin.yaml("scoreboard.yml");
        // Aeltere scoreboard.yml ohne die neuen Abschnitte: Standardwerte aus dem Plugin nehmen
        var res = plugin.getResource("scoreboard.yml");
        if (res != null) y.setDefaults(YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(res, java.nio.charset.StandardCharsets.UTF_8)));
        entries.clear();
        barEnabled = y.getBoolean("infobar.enabled", true);
        for (Map<?, ?> m : y.getMapList("infobar.entries")) {
            BarColor c;
            try { c = BarColor.valueOf(String.valueOf(m.get("color") == null ? "BLUE" : m.get("color")).toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { c = BarColor.BLUE; }
            Object sec = m.get("seconds");
            entries.add(new Entry(String.valueOf(m.get("text")), sec instanceof Number n ? Math.max(3, n.intValue()) : 15, c));
        }
        // ohne expliziten Standardwert lesen, damit die Plugin-Standardwerte greifen
        abEnabled = y.getBoolean("actionbar.enabled");
        abText = y.getString("actionbar.text");
        if (abText == null) abText = "";
        index = 0;
        cycleStart = System.currentTimeMillis();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Entry cur = null;
        double progress = 1;
        if (barEnabled && !entries.isEmpty()) {
            cur = entries.get(index % entries.size());
            long dur = cur.seconds * 1000L;
            if (now - cycleStart >= dur) {
                index = (index + 1) % entries.size();
                cycleStart = now;
                cur = entries.get(index);
                dur = cur.seconds * 1000L;
            }
            progress = 1.0 - (now - cycleStart) / (double) dur;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            var d = plugin.data().getIfLoaded(p.getUniqueId());
            if (d == null) continue;
            boolean busy = plugin.scoreboard().hasBossbar(p, "event") || plugin.scoreboard().hasBossbar(p, "war");
            if (cur != null && !busy && d.setting("bossbar", true)) {
                plugin.scoreboard().bossbar(p, "info", plugin.placeholders().apply(p, cur.text), progress, cur.color);
            } else {
                plugin.scoreboard().hideBossbar(p, "info");
            }
            if (abEnabled && d.setting("actionbar", true) && !Msg.actionbarBusy(p) && !plugin.combat().isTagged(p) && !abText.isEmpty()) {
                p.sendActionBar(Text.c(plugin.placeholders().apply(p, abText)));
            }
        }
    }
}
