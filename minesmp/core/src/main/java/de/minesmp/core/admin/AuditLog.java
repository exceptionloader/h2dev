package de.minesmp.core.admin;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import org.bukkit.command.CommandSender;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Protokolliert Teamaktionen und wichtige Systemvorgaenge in der Datenbank. */
public final class AuditLog {
    private final MineSMP plugin;

    public AuditLog(MineSMP plugin) {
        this.plugin = plugin;
        plugin.command(new SimpleCommand("auditlog", "minesmp.admin.audit", "Zeigt das Audit-Log", this::show, "audit"));
    }

    public void log(String actor, String action, String details) {
        plugin.db().update("INSERT INTO audit_log (time, actor, action, details) VALUES (?, ?, ?, ?)",
                System.currentTimeMillis(), actor, action, details.length() > 500 ? details.substring(0, 500) : details);
    }

    public void log(CommandSender actor, String action, String details) {
        log(actor.getName(), action, details);
    }

    private void show(CommandSender s, String label, String[] args) {
        String filter = args.length > 0 ? args[0] : null;
        plugin.db().queryThen(c -> {
            List<String> out = new ArrayList<>();
            String sql = filter == null ? "SELECT time, actor, action, details FROM audit_log ORDER BY id DESC LIMIT 15"
                    : "SELECT time, actor, action, details FROM audit_log WHERE actor = ? OR action = ? ORDER BY id DESC LIMIT 15";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (filter != null) { ps.setString(1, filter); ps.setString(2, filter.toUpperCase()); }
                try (ResultSet rs = ps.executeQuery()) {
                    SimpleDateFormat f = new SimpleDateFormat("dd.MM. HH:mm");
                    while (rs.next()) out.add("&8" + f.format(new Date(rs.getLong(1))) + " &b" + rs.getString(2) + " &7" + rs.getString(3) + " &f" + rs.getString(4));
                }
            }
            return out;
        }, lines -> {
            Msg.send(s, "audit.header", "&8&m----&r &b&lAudit-Log &8&m----");
            if (lines.isEmpty()) Msg.send(s, "audit.empty", "&7Keine Eintraege.");
            for (String l : lines) s.sendMessage(Text.c(l));
        });
    }
}
