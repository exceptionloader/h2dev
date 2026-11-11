package de.minesmp.core.admin;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.db.Database;
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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Report-System: /report oeffnet ein Kisten-Menue (Spieler melden oder Bug melden, dann Grund waehlen).
 * Das Team bekommt eine Chat-Nachricht mit den Knoepfen [Annehmen] (teleportiert zum Spieler) und [Ablehnen].
 * Teammitglieder sehen im selben Menue zusaetzlich alle offenen Reports. Gruende und Bug-Kategorien: config.yml -> reports.
 */
public final class ReportService implements Listener {
    private static final String BUG = "BUG";
    private static final int[] CHOICE_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    private record Report(long id, String reporterId, String reporter, String targetId, String target, String reason, long created, String location) {
        boolean bug() { return BUG.equals(targetId); }
    }

    private record Choice(String name, Material icon, boolean text) {}

    /** Wartet auf eine Beschreibung im Chat. */
    private record Pending(boolean bug, String targetId, String targetName, String category, long expires) {}

    private final MineSMP plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ReportService(MineSMP plugin) {
        this.plugin = plugin;
        plugin.listen(this);
        plugin.command(new SimpleCommand("report", "minesmp.report", "Spieler oder Bug melden", this::command, (s, a) -> a.length == 1 ? null : List.of(), "melden", "bugreport"));
        plugin.command(new SimpleCommand("reports", "minesmp.staff.reports", "Offene Reports", (s, l, a) -> {
            if (s instanceof Player p) openMain(p, 0);
        }));
    }

    private static boolean staff(CommandSender s) { return s.hasPermission("minesmp.staff.reports"); }

    // ================================================================ Einstellungen
    private List<Choice> choices(String path, List<Choice> def) {
        List<Choice> out = new ArrayList<>();
        for (Map<?, ?> m : plugin.getConfig().getMapList(path)) {
            Object name = m.get("name");
            if (name == null) continue;
            Material icon = Material.matchMaterial(String.valueOf(m.get("icon")));
            out.add(new Choice(String.valueOf(name), icon == null ? Material.PAPER : icon, Boolean.TRUE.equals(m.get("text"))));
        }
        return out.isEmpty() ? def : out;
    }

    private List<Choice> playerReasons() {
        return choices("reports.player-reasons", List.of(new Choice("Hacking / Cheating", Material.DIAMOND_SWORD, false),
                new Choice("Beleidigung", Material.OAK_SIGN, false), new Choice("Spam / Werbung", Material.PAPER, false),
                new Choice("Griefing", Material.TNT, false), new Choice("Bugusing", Material.SPIDER_EYE, false),
                new Choice("Betrug / Scam", Material.GOLD_INGOT, false), new Choice("Name oder Skin", Material.NAME_TAG, false),
                new Choice("Sonstiges", Material.FEATHER, true)));
    }

    private List<Choice> bugCategories() {
        return choices("reports.bug-categories", List.of(new Choice("Spielfehler / Glitch", Material.SPIDER_EYE, true),
                new Choice("Items oder Geld verloren", Material.CHEST, true), new Choice("Shop / Auktionshaus", Material.EMERALD, true),
                new Choice("Clan / Grundstueck", Material.SHIELD, true), new Choice("Spawn / Welt", Material.GRASS_BLOCK, true),
                new Choice("Befehl funktioniert nicht", Material.COMMAND_BLOCK, true), new Choice("Sonstiges", Material.FEATHER, true)));
    }

    // ================================================================ Befehl
    private void command(CommandSender s, String label, String[] a) {
        if (!(s instanceof Player p)) return;
        if (a.length == 0) { openMain(p, 0); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (staff(p) && a.length >= 2 && (sub.equals("accept") || sub.equals("annehmen") || sub.equals("deny") || sub.equals("ablehnen"))) {
            long id;
            try { id = Long.parseLong(a[1].replace("#", "")); } catch (NumberFormatException e) { return; }
            handle(p, id, sub.equals("accept") || sub.equals("annehmen"));
            return;
        }
        if (sub.equals("bug")) { openBug(p); return; }
        // /report <Spieler> [Grund] - wie frueher, ohne Grund oeffnet sich die Auswahl
        OfflinePlayer t = Bukkit.getPlayerExact(a[0]);
        if (t == null) t = Bukkit.getOfflinePlayerIfCached(a[0]);
        if (t == null || t.getName() == null || (t.isOnline() && !p.canSee(t.getPlayer()))) {
            Msg.send(p, "player-unknown", "{prefix}&cSpieler &f{name} &cwar noch nie auf MineSMP.", "name", a[0]);
            return;
        }
        if (t.getUniqueId().equals(p.getUniqueId())) { Msg.send(p, "report.self", "{prefix}&cDu kannst dich nicht selbst melden."); return; }
        if (a.length == 1) openReasons(p, t.getUniqueId().toString(), t.getName());
        else submit(p, false, t.getUniqueId().toString(), t.getName(), String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
    }

    // ================================================================ Menues
    private void openMain(Player p, int page) {
        if (!staff(p)) { buildMain(p, null, 0); return; }
        loadOpen(list -> { if (p.isOnline()) buildMain(p, list, page); });
    }

    private void buildMain(Player p, List<Report> open, int page) {
        boolean team = open != null;
        Menu m = new Menu(team ? 6 : 3, team ? "&8Report &7┃ " + (open.isEmpty() ? "&akeine offen" : "&c" + open.size() + " offen") : "&8Report") {};
        int row = team ? 0 : 9;
        m.set(row + 2, Items.of(Material.PLAYER_HEAD, "&c&lSpieler melden", "&7Jemand cheatet, beleidigt, griefed", "&7oder haelt sich nicht an die Regeln?", "", "&bKlicken &7» Spieler auswaehlen"), e -> openPlayers(p, 0));
        m.set(row + 4, Items.of(Material.BOOK, "&b&lReport", "&7Waehle aus, was du melden moechtest.", "&7Das Team bekommt sofort eine Nachricht.", "", "&8Falsche Reports werden bestraft."));
        m.set(row + 6, Items.of(Material.WRITABLE_BOOK, "&e&lBug melden", "&7Etwas funktioniert nicht richtig,", "&7Items sind weg oder ein Fehler tritt auf?", "", "&bKlicken &7» Kategorie auswaehlen"), e -> openBug(p));
        if (team) {
            for (int i = 9; i < 18; i++) m.set(i, Menu.FILLER);
            m.set(13, Items.of(Material.HOPPER, "&f&lOffene Reports", "&7Linksklick » annehmen und hinteleportieren", "&7Rechtsklick » ablehnen"));
            int pages = Math.max(1, (open.size() + 26) / 27), pg = Math.max(0, Math.min(page, pages - 1));
            for (int i = 0; i < 27 && pg * 27 + i < open.size(); i++) {
                Report r = open.get(pg * 27 + i);
                m.set(18 + i, reportItem(r), e -> {
                    Menu.close(p);
                    handle(p, r.id, e.getClick() != ClickType.RIGHT && e.getClick() != ClickType.SHIFT_RIGHT);
                });
            }
            for (int i = 45; i < 54; i++) m.set(i, Menu.FILLER);
            if (pg > 0) m.set(45, Items.of(Material.ARROW, "&7« Vorherige Seite"), e -> openMain(p, pg - 1));
            m.set(49, Items.of(Material.SUNFLOWER, "&eAktualisieren", "&7Seite " + (pg + 1) + " von " + pages), e -> openMain(p, pg));
            if (pg < pages - 1) m.set(53, Items.of(Material.ARROW, "&7Naechste Seite »"), e -> openMain(p, pg + 1));
        } else {
            m.fillEmpty();
        }
        m.open(p);
    }

    private org.bukkit.inventory.ItemStack reportItem(Report r) {
        List<String> lore = new ArrayList<>();
        lore.add((r.bug() ? "&7Beschreibung: &f" : "&7Grund: &f") + r.reason);
        lore.add("&7Von: &f" + r.reporter);
        lore.add("&7Vor: &f" + TimeUtil.format(System.currentTimeMillis() - r.created));
        lore.add("");
        lore.add("&aLinksklick &7» Annehmen" + (r.bug() ? " (zum Melder teleportieren)" : " (zum Spieler teleportieren)"));
        lore.add("&cRechtsklick &7» Ablehnen");
        if (r.bug()) return Items.of(Material.WRITABLE_BOOK, 1, "&e#" + r.id + " &fBug", lore);
        Player t = Bukkit.getPlayerExact(r.target);
        String name = "&c#" + r.id + " &f" + r.target + (t == null ? " &8(offline)" : "");
        return t != null ? Items.head(t, name, lore) : Items.of(Material.PAPER, 1, name, lore);
    }

    private void openPlayers(Player p, int page) {
        List<Player> list = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p) && p.canSee(o)) list.add(o);
        list.sort((x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        int pages = Math.max(1, (list.size() + 44) / 45), pg = Math.max(0, Math.min(page, pages - 1));
        Menu m = new Menu(6, "&8Wen moechtest du melden?") {};
        for (int i = 0; i < 45 && pg * 45 + i < list.size(); i++) {
            Player o = list.get(pg * 45 + i);
            String id = o.getUniqueId().toString(), name = o.getName();
            m.set(i, Items.head(o, "&f" + name, List.of("&bKlicken &7» diesen Spieler melden")), e -> openReasons(p, id, name));
        }
        if (list.isEmpty()) m.set(22, Items.of(Material.BARRIER, "&cNiemand online", "&7Ausser dir ist gerade kein Spieler da.", "&7Offline-Spieler: &f/report <Name>"));
        for (int i = 45; i < 54; i++) m.set(i, Menu.FILLER);
        m.set(45, Items.of(Material.ARROW, "&7« Zurueck"), e -> openMain(p, 0));
        if (pg > 0) m.set(48, Items.of(Material.ARROW, "&7« Vorherige Seite"), e -> openPlayers(p, pg - 1));
        m.set(49, Items.of(Material.NAME_TAG, "&fSpieler nicht dabei?", "&7Offline-Spieler meldest du mit", "&f/report <Name>"));
        if (pg < pages - 1) m.set(50, Items.of(Material.ARROW, "&7Naechste Seite »"), e -> openPlayers(p, pg + 1));
        m.open(p);
    }

    private void openReasons(Player p, String targetId, String targetName) {
        List<Choice> list = playerReasons();
        Menu m = new Menu(list.size() > 7 ? 4 : 3, "&8Grund: &c" + targetName) {};
        for (int i = 0; i < list.size() && i < CHOICE_SLOTS.length; i++) {
            Choice c = list.get(i);
            m.set(CHOICE_SLOTS[i], Items.of(c.icon, "&c" + c.name, c.text ? "&bKlicken &7» Grund im Chat beschreiben" : "&bKlicken &7» Report absenden"), e -> {
                Menu.close(p);
                if (c.text) ask(p, new Pending(false, targetId, targetName, c.name, 0));
                else submit(p, false, targetId, targetName, c.name);
            });
        }
        m.set(m.getInventory().getSize() - 9, Items.of(Material.ARROW, "&7« Zurueck"), e -> openPlayers(p, 0));
        m.fillEmpty();
        m.open(p);
    }

    private void openBug(Player p) {
        List<Choice> list = bugCategories();
        Menu m = new Menu(list.size() > 7 ? 4 : 3, "&8Was fuer ein Bug?") {};
        for (int i = 0; i < list.size() && i < CHOICE_SLOTS.length; i++) {
            Choice c = list.get(i);
            m.set(CHOICE_SLOTS[i], Items.of(c.icon, "&e" + c.name, "&bKlicken &7» danach kurz im Chat beschreiben"), e -> {
                Menu.close(p);
                ask(p, new Pending(true, BUG, "Bug", c.name, 0));
            });
        }
        m.set(m.getInventory().getSize() - 9, Items.of(Material.ARROW, "&7« Zurueck"), e -> openMain(p, 0));
        m.fillEmpty();
        m.open(p);
    }

    // ================================================================ Beschreibung im Chat
    private void ask(Player p, Pending pd) {
        pending.put(p.getUniqueId(), new Pending(pd.bug, pd.targetId, pd.targetName, pd.category, System.currentTimeMillis() + 120_000));
        Msg.send(p, pd.bug ? "report.ask-bug" : "report.ask-reason",
                pd.bug ? "{prefix}&eBeschreibe den Bug jetzt kurz im Chat &7(was hast du gemacht, was ist passiert?). &8Abbrechen: &fabbrechen"
                        : "{prefix}&eSchreibe jetzt in den Chat, was &f{name} &egemacht hat. &8Abbrechen: &fabbrechen", "name", pd.targetName);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.4f);
    }

    /** Wird vom Chat aufgerufen (asynchron): true = die Nachricht war eine Report-Beschreibung und erscheint nicht im Chat. */
    public boolean consumeChat(Player p, String raw) {
        Pending pd = pending.remove(p.getUniqueId());
        if (pd == null || pd.expires < System.currentTimeMillis()) return false;
        String text = raw.trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (text.isEmpty() || text.equalsIgnoreCase("abbrechen") || text.equalsIgnoreCase("cancel")) {
                Msg.send(p, "report.cancelled", "{prefix}&7Report abgebrochen.");
                return;
            }
            submit(p, pd.bug, pd.targetId, pd.targetName, pd.bug ? pd.category + ": " + text : text);
        });
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { pending.remove(e.getPlayer().getUniqueId()); }

    // ================================================================ Report anlegen
    private void submit(Player p, boolean bug, String targetId, String targetName, String reasonRaw) {
        long cd = plugin.cooldowns().remaining(p.getUniqueId(), "report");
        if (cd > 0) { Msg.send(p, "cooldown", "{prefix}&cBitte warte noch &f{time}&c.", "time", TimeUtil.format(cd)); return; }
        String reason = reasonRaw.length() > 200 ? reasonRaw.substring(0, 200) : reasonRaw;
        Player target = bug ? null : Bukkit.getPlayer(UUID.fromString(targetId));
        String loc = PlayerData.locToString(target != null ? target.getLocation() : p.getLocation());
        String reporterId = p.getUniqueId().toString(), reporter = p.getName();
        int maxOpen = Math.max(1, plugin.getConfig().getInt("reports.max-open", 3));
        long now = System.currentTimeMillis();
        plugin.db().queryThen(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), SUM(CASE WHEN target = ? THEN 1 ELSE 0 END) FROM reports WHERE reporter = ? AND status = 'OPEN'")) {
                ps.setString(1, targetId);
                ps.setString(2, reporterId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if (!bug && rs.getInt(2) > 0) return -2L;
                        if (rs.getInt(1) >= maxOpen) return -1L;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO reports (reporter, reporter_name, target, target_name, reason, created, status, location) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                Database.bind(ps, reporterId, reporter, targetId, targetName, reason, now, "OPEN", loc);
                ps.executeUpdate();
                return Database.insertId(ps);
            }
        }, id -> {
            if (id == -2) { Msg.send(p, "report.duplicate", "{prefix}&7Du hast &f{name} &7schon gemeldet - das Team kuemmert sich darum.", "name", targetName); return; }
            if (id == -1) { Msg.send(p, "report.too-many", "{prefix}&cDu hast schon {n} offene Reports. Bitte warte, bis das Team sie bearbeitet hat.", "n", String.valueOf(maxOpen)); return; }
            plugin.cooldowns().set(p.getUniqueId(), "report", plugin.getConfig().getLong("cooldowns.report", 60) * 1000);
            if (bug) Msg.send(p, "report.sent-bug", "{prefix}&aDanke! Dein Bug-Report &f#{id} &awurde an das Team gesendet.", "id", String.valueOf(id));
            else Msg.send(p, "report.sent", "{prefix}&aDanke! Dein Report &f#{id} &agegen &f{name} &awurde an das Team gesendet.", "id", String.valueOf(id), "name", targetName);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
            Report r = new Report(id, reporterId, reporter, targetId, targetName, reason, now, loc);
            for (Player st : Bukkit.getOnlinePlayers()) if (staff(st)) announce(st, r);
            Bukkit.getConsoleSender().sendMessage(headline(r));
            plugin.webhook().send("reports", bug ? "Neuer Bug-Report #" + id : "Neuer Report #" + id + ": " + targetName, "Von: " + reporter + "\n" + (bug ? "Beschreibung: " : "Grund: ") + reason, 0xFB8C00);
        });
    }

    private Component headline(Report r) {
        return Text.c(r.bug() ? "&e&lBUG &8#" + r.id + " &8┃ &f" + r.reporter + "&7: &f" + r.reason
                : "&c&lREPORT &8#" + r.id + " &8┃ &f" + r.reporter + " &7meldet &c" + r.target + "&7: &f" + r.reason);
    }

    /** Chat-Nachricht fuer ein Teammitglied mit anklickbaren Knoepfen. */
    private void announce(Player st, Report r) {
        st.sendMessage(headline(r));
        Component accept = Text.c("&a&l[✔ Annehmen]").clickEvent(ClickEvent.runCommand("/report accept " + r.id))
                .hoverEvent(HoverEvent.showText(Text.c(r.bug() ? "&7Annehmen und zum Melder teleportieren" : "&7Annehmen und zu &f" + r.target + " &7teleportieren")));
        Component deny = Text.c("&c&l[✘ Ablehnen]").clickEvent(ClickEvent.runCommand("/report deny " + r.id))
                .hoverEvent(HoverEvent.showText(Text.c("&7Report ablehnen und schliessen")));
        st.sendMessage(Component.text(" ").append(accept).append(Component.text("  ")).append(deny).append(Text.c("  &8(alle offenen: /report)")));
        st.playSound(st.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
    }

    // ================================================================ Annehmen / Ablehnen
    private void handle(Player staff, long id, boolean accept) {
        String name = staff.getName();
        plugin.db().queryThen(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE reports SET status = ?, handler = ? WHERE id = ? AND status = 'OPEN'")) {
                Database.bind(ps, accept ? "ACCEPTED" : "DENIED", name, id);
                if (ps.executeUpdate() == 0) return new Report(-1, "", "", "", "", "", 0, "");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT id, reporter, reporter_name, target, target_name, reason, created, location FROM reports WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Report(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8))
                            : new Report(-1, "", "", "", "", "", 0, "");
                }
            }
        }, r -> {
            if (r.id < 0) { Msg.send(staff, "report.gone", "{prefix}&7Report &f#{id} &7wurde schon bearbeitet.", "id", String.valueOf(id)); return; }
            plugin.audit().log(staff, accept ? "REPORT_ACCEPT" : "REPORT_DENY", "#" + r.id + " " + (r.bug() ? "Bug von " + r.reporter : r.target));
            for (Player st : Bukkit.getOnlinePlayers()) {
                if (staff(st) && !st.equals(staff)) Msg.send(st, accept ? "report.team-accepted" : "report.team-denied",
                        accept ? "&8[&cTeam&8] &b{staff} &7hat Report &f#{id} &aangenommen&7." : "&8[&cTeam&8] &b{staff} &7hat Report &f#{id} &cabgelehnt&7.", "staff", name, "id", String.valueOf(r.id));
            }
            Player reporter = uuid(r.reporterId) == null ? null : Bukkit.getPlayer(uuid(r.reporterId));
            if (!accept) {
                Msg.send(staff, "report.denied", "{prefix}&7Report &f#{id} &7abgelehnt und geschlossen.", "id", String.valueOf(r.id));
                if (reporter != null) Msg.send(reporter, "report.you-denied", "{prefix}&7Dein Report &f#{id} &7wurde vom Team geprueft und geschlossen.", "id", String.valueOf(r.id));
                return;
            }
            if (reporter != null && !reporter.equals(staff)) Msg.send(reporter, "report.you-accepted", "{prefix}&aEin Teammitglied kuemmert sich jetzt um deinen Report &f#{id}&a. Danke!", "id", String.valueOf(r.id));
            // Ziel: gemeldeter Spieler (bei Bugs der Melder); offline -> Ort zum Zeitpunkt des Reports
            Player goal = r.bug() ? reporter : (uuid(r.targetId) == null ? null : Bukkit.getPlayer(uuid(r.targetId)));
            Location loc = goal != null ? goal.getLocation() : PlayerData.locFromString(r.location);
            if (!r.bug() && plugin.getConfig().getBoolean("reports.vanish-on-accept", true) && staff.hasPermission("minesmp.staff.vanish")
                    && plugin.vanish() != null && !plugin.vanish().isVanished(staff)) {
                plugin.vanish().enable(staff, VanishService.Mode.FULL, true);
            }
            if (loc != null && (goal == null || !goal.equals(staff))) plugin.ranks().teleportNow(staff, loc);
            Msg.send(staff, "report.accepted", "{prefix}&aReport &f#{id} &aangenommen. &7{where}", "id", String.valueOf(r.id),
                    "where", loc == null ? "Kein Ort gespeichert." : goal != null ? "Du bist jetzt bei &f" + goal.getName() + "&7." : "&f" + (r.bug() ? r.reporter : r.target) + " &7ist offline - du bist am Ort des Reports.");
            staff.sendMessage(Text.c(" &8» " + (r.bug() ? "&7Beschreibung: &f" : "&7Grund: &f") + r.reason + " &8(von " + r.reporter + ")"));
        });
    }

    private static UUID uuid(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private void loadOpen(java.util.function.Consumer<List<Report>> then) {
        plugin.db().queryThen(c -> {
            List<Report> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, reporter, reporter_name, target, target_name, reason, created, location FROM reports WHERE status = 'OPEN' ORDER BY id ASC LIMIT 270");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Report(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8)));
            }
            return out;
        }, then);
    }

    /** Teammitglieder sehen beim Einloggen, wie viele Reports offen sind. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !staff(p)) return;
            loadOpen(list -> {
                if (list.isEmpty() || !p.isOnline()) return;
                p.sendMessage(Text.c(Msg.get("report.join", "{prefix}&7Es gibt &c{n} &7offene Reports. ", "n", String.valueOf(list.size())))
                        .append(Text.c("&b&l[Oeffnen]").clickEvent(ClickEvent.runCommand("/report")).hoverEvent(HoverEvent.showText(Text.c("&7Report-Menue oeffnen")))));
            });
        }, 60);
    }
}
