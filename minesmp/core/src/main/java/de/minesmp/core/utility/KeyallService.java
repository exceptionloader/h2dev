package de.minesmp.core.utility;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Keyall: In festen Abstaenden (z.B. jede volle Stunde) bekommen alle Online-Spieler Schluessel.
 * Countdown im Scoreboard (%keyall%), Vorwarnungen im Chat.
 */
public final class KeyallService {
    private final MineSMP plugin;
    private long next;

    public KeyallService(MineSMP plugin) {
        this.plugin = plugin;
        next = computeNext();
        plugin.command(new SimpleCommand("keyall", "minesmp.keyall", "Naechstes Keyall", (s, l, a) -> {
            if (a.length >= 1 && a[0].equalsIgnoreCase("now") && s.hasPermission("minesmp.admin.keyall")) {
                payout();
                plugin.audit().log(s, "KEYALL", "manuell");
                return;
            }
            Msg.send(s, "keyall.info", "{prefix}&7Naechstes &6Keyall &7in &f{time}&7: &e{reward}", "time", remainingText(), "reward", rewardText());
        }, (s, a) -> a.length == 1 && s.hasPermission("minesmp.admin.keyall") ? List.of("now") : List.of()));
        Bukkit.getScheduler().runTaskTimer(plugin, this::second, 20, 20);
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("keyall");
        return s == null ? new org.bukkit.configuration.MemoryConfiguration() : s;
    }

    private boolean enabled() { return cfg().getBoolean("enabled", true); }

    private long computeNext() {
        int interval = Math.max(5, cfg().getInt("interval-minutes", 60));
        ZonedDateTime now = ZonedDateTime.now(RewardService.ZONE);
        ZonedDateTime midnight = now.toLocalDate().atStartOfDay(RewardService.ZONE);
        long minutes = Duration.between(midnight, now).toMinutes();
        long slot = (minutes / interval + 1) * interval;
        return midnight.plusMinutes(slot).toInstant().toEpochMilli();
    }

    public long remaining() {
        return Math.max(0, next - System.currentTimeMillis());
    }

    public String remainingText() {
        if (!enabled()) return "-";
        long s = remaining() / 1000;
        return s >= 3600 ? (s / 3600) + "h " + (s % 3600 / 60) + "m" : s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    private String rewardText() {
        return cfg().getString("display", "&e1x Vote-Schluessel");
    }

    private void second() {
        if (!enabled()) return;
        long left = remaining() / 1000;
        if (left <= 0) {
            payout();
            next = computeNext();
            if (next - System.currentTimeMillis() < 30_000) next += Math.max(5, cfg().getInt("interval-minutes", 60)) * 60_000L;
            return;
        }
        for (int w : cfg().getIntegerList("warn-seconds").isEmpty() ? List.of(300, 60, 10, 3, 2, 1) : cfg().getIntegerList("warn-seconds")) {
            if (left == w) {
                Msg.broadcast("keyall.soon", "{prefix}&6&lKeyall &7in &f{time}&7! Belohnung: {reward}", "time", w >= 60 ? (w / 60) + " Min." : w + " Sek.", "reward", rewardText());
                if (w <= 3) for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.2f);
            }
        }
    }

    public void payout() {
        int min = cfg().getInt("min-online", 1);
        if (Bukkit.getOnlinePlayers().size() < min) {
            Msg.broadcast("keyall.skipped", "{prefix}&7Keyall uebersprungen - zu wenige Spieler online.");
            return;
        }
        boolean skipAfk = cfg().getBoolean("skip-afk", true);
        ConfigurationSection reward = cfg().getConfigurationSection("reward");
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (skipAfk && plugin.afk().isAfk(p)) continue;
            if (reward != null) plugin.rewards().give(p, reward, "Keyall");
            p.showTitle(Title.title(Text.c("&6&lKEYALL"), Text.c("&7Du hast " + rewardText() + " &7bekommen!"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(500))));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.2f);
            n++;
        }
        Msg.broadcast("keyall.done", "{prefix}&6&lKeyall! &f{n} Spieler &7haben {reward} &7erhalten.", "n", String.valueOf(n), "reward", rewardText());
    }
}
