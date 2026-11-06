package de.minesmp.core.admin;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** /report und /reports (Team-GUI). */
public final class ReportService {
    private final MineSMP plugin;

    private record Report(long id, String reporter, String target, String reason, long created, String location) {}

    public ReportService(MineSMP plugin) {
        this.plugin = plugin;
        plugin.command(new SimpleCommand("report", "minesmp.report", "Meldet einen Spieler", this::report,
                (s, a) -> a.length == 1 ? null : List.of("Hacking", "Beleidigung", "Spam", "Griefing", "Bugusing"), "melden"));
        plugin.command(new SimpleCommand("reports", "minesmp.staff.reports", "Offene Reports", (s, l, a) -> {
            if (s instanceof Player p) openMenu(p);
        }));
    }

    private void report(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        if (a.length < 2) {
            Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/report <Spieler> <Grund>");
            return;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null || t.equals(p)) {
            Msg.send(p, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a[0]);
            return;
        }
        long cd = plugin.cooldowns().remaining(p.getUniqueId(), "report");
        if (cd > 0) {
            Msg.send(p, "cooldown", "{prefix}&cBitte warte noch &f{time}&c.", "time", TimeUtil.format(cd));
            return;
        }
        plugin.cooldowns().set(p.getUniqueId(), "report", plugin.getConfig().getLong("cooldowns.report", 60) * 1000);
        String reason = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
        if (reason.length() > 200) reason = reason.substring(0, 200);
        String loc = PlayerData.locToString(t.getLocation());
        plugin.db().update("INSERT INTO reports (reporter, reporter_name, target, target_name, reason, created, status, location) VALUES (?,?,?,?,?,?,?,?)",
                p.getUniqueId().toString(), p.getName(), t.getUniqueId().toString(), t.getName(), reason, System.currentTimeMillis(), "OPEN", loc);
        Msg.send(p, "report.sent", "{prefix}&aDanke! Dein Report gegen &f{name} &awurde an das Team gesendet.", "name", t.getName());
        for (Player st : Bukkit.getOnlinePlayers()) {
            if (st.hasPermission("minesmp.staff.reports")) {
                Msg.send(st, "report.staff", "&c&lREPORT &8| &f{reporter} &7meldet &c{target}&7: &f{reason} &8(/reports)",
                        "reporter", p.getName(), "target", t.getName(), "reason", reason);
            }
        }
        plugin.webhook().send("reports", "Neuer Report: " + t.getName(), "Von: " + p.getName() + "\nGrund: " + reason, 0xFB8C00);
    }

    private void openMenu(Player staff) {
        plugin.db().queryThen(c -> {
            List<Report> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, reporter_name, target_name, reason, created, location FROM reports WHERE status = 'OPEN' ORDER BY id ASC LIMIT 45")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Report(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6)));
                }
            }
            return out;
        }, list -> {
            Menu m = new Menu(6, "&8Offene Reports &7(" + list.size() + ")") {};
            int slot = 0;
            for (Report r : list) {
                m.set(slot++, Items.of(Material.PAPER, 1, "&c#" + r.id + " &f" + r.target, List.of(
                        "&7Grund: &f" + r.reason,
                        "&7Von: &f" + r.reporter,
                        "&7Vor: &f" + TimeUtil.format(System.currentTimeMillis() - r.created),
                        "",
                        "&bLinksklick &7» Zum Spieler / Ort teleportieren",
                        "&cRechtsklick &7» Als erledigt markieren")), e -> {
                    if (e.getClick() == ClickType.RIGHT) {
                        plugin.db().update("UPDATE reports SET status = 'CLOSED', handler = ? WHERE id = ?", staff.getName(), r.id);
                        plugin.audit().log(staff, "REPORT_CLOSE", "#" + r.id + " " + r.target);
                        Msg.send(staff, "report.closed", "{prefix}&aReport &f#{id} &ageschlossen.", "id", String.valueOf(r.id));
                        staff.closeInventory();
                        Bukkit.getScheduler().runTaskLater(plugin, () -> openMenu(staff), 5);
                    } else {
                        Player t = Bukkit.getPlayerExact(r.target);
                        Location loc = t != null ? t.getLocation() : PlayerData.locFromString(r.location);
                        if (loc != null) plugin.ranks().teleport(staff, loc);
                        staff.closeInventory();
                    }
                });
            }
            m.open(staff);
        });
    }
}
