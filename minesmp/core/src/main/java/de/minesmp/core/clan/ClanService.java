package de.minesmp.core.clan;

import de.minesmp.core.MineSMP;
import de.minesmp.core.clan.Clan.Rank;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.ConfirmMenu;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.boss.BarColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Clans: Raenge, Bank, Home, Level/XP, Allianzen, Rivalen, Kriege, Clanchat, Rangliste, GUI. */
public final class ClanService implements Listener {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern TAG = Pattern.compile("^[A-Za-z0-9]{2,5}$");

    public static final class War {
        public final Clan a, b;
        public final long end;
        public final double pot;
        public int killsA, killsB;
        War(Clan a, Clan b, long end, double pot) { this.a = a; this.b = b; this.end = end; this.pot = pot; }
    }

    private record WarRequest(String fromClan, double stake, long time) {}

    private final MineSMP plugin;
    private final Map<String, Clan> clans = new HashMap<>();
    private final Map<UUID, Clan> byMember = new HashMap<>();
    private final Map<UUID, Set<String>> invites = new HashMap<>();
    private final Map<String, String> allyRequests = new HashMap<>(); // zielClan -> anfragenderClan
    private final Map<String, WarRequest> warRequests = new HashMap<>();
    private final Map<String, Long> warCooldown = new HashMap<>();
    private final List<War> wars = new ArrayList<>();
    private final Set<UUID> clanChatToggle = new HashSet<>();

    public ClanService(MineSMP plugin) {
        this.plugin = plugin;
        load();
        plugin.listen(this);
        plugin.command(new SimpleCommand("clan", "minesmp.clan", "Clan-System", this::cmd, this::complete, "c", "gilde"));
        plugin.command(new SimpleCommand("cc", "minesmp.clan", "Clanchat", (s, l, a) -> {
            if (s instanceof Player p) chat(p, String.join(" ", a));
        }, "clanchat"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickWars, 20, 20);
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    private void load() {
        try {
            plugin.db().query(c -> {
                List<Clan> out = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT id, name, tag, data FROM clans"); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Clan cl = new Clan(rs.getString(1), rs.getString(2), rs.getString(3));
                        cl.deserialize(rs.getString(4));
                        out.add(cl);
                    }
                }
                return out;
            }).get(30, TimeUnit.SECONDS).forEach(cl -> {
                clans.put(cl.id, cl);
                cl.members.keySet().forEach(u -> byMember.put(u, cl));
            });
            plugin.getLogger().info(clans.size() + " Clans geladen.");
        } catch (Exception e) {
            plugin.getLogger().severe("Clans konnten nicht geladen werden: " + e.getMessage());
        }
    }

    public void save(Clan c) {
        plugin.db().update("REPLACE INTO clans (id, name, tag, data) VALUES (?, ?, ?, ?)", c.id, c.name, c.tag, c.serialize());
    }

    public void saveAll() {
        clans.values().forEach(this::save);
    }

    public Clan of(Player p) { return byMember.get(p.getUniqueId()); }
    public Clan of(UUID id) { return byMember.get(id); }
    public java.util.Collection<Clan> all() { return clans.values(); }

    public Clan byName(String name) {
        for (Clan c : clans.values()) if (c.name.equalsIgnoreCase(name) || c.tag.equalsIgnoreCase(name)) return c;
        return null;
    }

    /** Formatierter Clan-Tag fuer Chat/Tab/Nametag, leer wenn kein Clan. */
    public String tag(Player p) {
        Clan c = of(p);
        if (c == null) return "";
        return Text.replace(cfg().getString("clans.tag-format", "&8[&b{tag}&8] "), "tag", c.tag, "level", String.valueOf(c.level));
    }

    public boolean sameOrAllied(UUID a, UUID b) {
        Clan ca = byMember.get(a), cb = byMember.get(b);
        if (ca == null || cb == null) return false;
        return ca == cb || ca.allies.contains(cb.id);
    }

    public int maxMembers(Clan c) {
        return cfg().getInt("clans.base-members", 8) + (c.level - 1) * cfg().getInt("clans.members-per-level", 2);
    }

    public double xpForNext(Clan c) {
        return cfg().getDouble("clans.xp-base", 1000) * Math.pow(c.level, 1.5);
    }

    public void addXp(Clan c, double amount) {
        if (c == null || amount <= 0) return;
        c.xp += amount;
        int max = cfg().getInt("clans.max-level", 50);
        while (c.level < max && c.xp >= xpForNext(c)) {
            c.xp -= xpForNext(c);
            c.level++;
            broadcastClan(c, Msg.get("clan.levelup", "{prefix}&b&lCLAN LEVEL UP! &7Dein Clan ist jetzt &bLevel {level}&7! &8(Max. Mitglieder: {max})",
                    "level", String.valueOf(c.level), "max", String.valueOf(maxMembers(c))));
        }
        save(c);
    }

    public void broadcastClan(Clan c, String msg) {
        for (UUID u : c.members.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(Text.c(msg));
        }
    }

    public List<Clan> top() {
        List<Clan> list = new ArrayList<>(clans.values());
        list.sort(Comparator.comparingInt((Clan c) -> c.level).thenComparingDouble(c -> c.xp).reversed());
        return list;
    }

    /** Saison-Rangliste: Punkte, dann Siege, dann Clan-Level. */
    public List<Clan> seasonTop() {
        List<Clan> list = new ArrayList<>(clans.values());
        list.sort(Comparator.comparingInt(Clan::seasonPoints).thenComparingInt(c -> c.seasonWins)
                .thenComparingInt(c -> c.level).thenComparingDouble(c -> c.xp).reversed());
        return list;
    }

    public int position(Clan c) {
        return top().indexOf(c) + 1;
    }

    // ---------------- Befehle ----------------

    private List<String> complete(CommandSender s, String[] a) {
        if (a.length == 1) return List.of("create", "invite", "join", "leave", "kick", "promote", "demote", "home", "sethome", "chat",
                "top", "info", "bank", "ally", "unally", "rival", "unrival", "war", "disband", "transfer", "ff", "menu");
        if (a.length == 2 && List.of("invite", "kick", "promote", "demote", "transfer").contains(a[0].toLowerCase())) return null;
        if (a.length == 2 && List.of("join", "info", "ally", "unally", "rival", "unrival", "war").contains(a[0].toLowerCase())) {
            List<String> n = new ArrayList<>();
            clans.values().forEach(c -> n.add(c.name));
            if (a[0].equalsIgnoreCase("war")) n.add("accept");
            return n;
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("bank")) return List.of("deposit", "withdraw");
        return List.of();
    }

    private void cmd(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        if (a.length == 0 || a[0].equalsIgnoreCase("menu")) { openMenu(p); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create", "erstellen" -> create(p, a);
            case "invite", "einladen" -> invite(p, a);
            case "join", "beitreten" -> join(p, a);
            case "leave", "verlassen" -> leave(p);
            case "kick" -> kick(p, a);
            case "promote" -> changeRank(p, a, true);
            case "demote" -> changeRank(p, a, false);
            case "home" -> home(p);
            case "sethome" -> setHome(p);
            case "chat" -> {
                if (a.length == 1) {
                    if (!clanChatToggle.remove(p.getUniqueId())) clanChatToggle.add(p.getUniqueId());
                    Msg.send(p, "clan.chat-toggle", "{prefix}&7Clanchat-Modus: {state}", "state", clanChatToggle.contains(p.getUniqueId()) ? "&aan" : "&caus");
                } else chat(p, String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
            }
            case "top" -> top(p);
            case "info" -> info(p, a.length > 1 ? byName(a[1]) : of(p));
            case "bank" -> bank(p, a);
            case "ally" -> ally(p, a);
            case "unally" -> unally(p, a);
            case "rival" -> rival(p, a, true);
            case "unrival" -> rival(p, a, false);
            case "war", "krieg" -> war(p, a);
            case "disband", "aufloesen" -> disband(p);
            case "season", "saison" -> {
                if (a.length >= 2 && a[1].equalsIgnoreCase("reset") && p.hasPermission("minesmp.admin.clan")) {
                    for (Clan c : clans.values()) { c.seasonWins = 0; c.seasonDraws = 0; c.seasonLosses = 0; save(c); }
                    plugin.audit().log(p, "CLAN_SEASON_RESET", "alle Clans");
                    Msg.broadcast("clan.season-reset", "{prefix}&b&lNEUE CLAN-SAISON! &7Alle Saison-Punkte wurden zurueckgesetzt.");
                } else {
                    Msg.send(p, "clan.season-info", "{prefix}&7Die Clan-Rangliste steht am Spawn. Punkte: &aSieg 3 &8| &7Unentschieden 1 &8| &cNiederlage 0");
                }
            }
            case "transfer" -> transfer(p, a);
            case "ff" -> {
                Clan c = require(p, Rank.CO_LEADER);
                if (c == null) return;
                c.friendlyFire = !c.friendlyFire;
                save(c);
                broadcastClan(c, Msg.get("clan.ff", "{prefix}&7Friendly Fire ist jetzt {state}&7.", "state", c.friendlyFire ? "&caktiv" : "&adeaktiviert"));
            }
            default -> help(p);
        }
    }

    private void help(Player p) {
        Msg.send(p, "clan.help", "&8&m-----&r &b&lClans &8&m-----\n&b/clan create <Name> <Tag> &8- &7Clan gruenden\n&b/clan invite|kick|promote|demote <Spieler>\n"
                + "&b/clan join <Clan> &8- &7Einladung annehmen\n&b/clan home|sethome &8- &7Clan-Home\n&b/clan bank deposit|withdraw <Betrag>\n"
                + "&b/clan ally|unally|rival|unrival <Clan>\n&b/clan war <Clan> <Einsatz> &8| &b/clan war accept\n&b/clan chat [Nachricht] &8- &7oder &b/cc\n"
                + "&b/clan top|info|leave|disband|transfer|ff");
    }

    private Clan require(Player p, Rank min) {
        Clan c = of(p);
        if (c == null) {
            Msg.send(p, "clan.none", "{prefix}&cDu bist in keinem Clan.");
            return null;
        }
        if (!c.rank(p.getUniqueId()).atLeast(min)) {
            Msg.send(p, "clan.rank-too-low", "{prefix}&cDafuer brauchst du mindestens den Rang {rank}&c.", "rank", min.display);
            return null;
        }
        return c;
    }

    private void create(Player p, String[] a) {
        if (a.length < 3) { Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/clan create <Name> <Tag>"); return; }
        if (of(p) != null) { Msg.send(p, "clan.already", "{prefix}&cDu bist bereits in einem Clan."); return; }
        String name = a[1], tag = a[2];
        if (!NAME.matcher(name).matches() || !TAG.matcher(tag).matches()) {
            Msg.send(p, "clan.bad-name", "{prefix}&cName: 3-16 Zeichen (A-Z, 0-9, _), Tag: 2-5 Zeichen (A-Z, 0-9).");
            return;
        }
        for (String bad : cfg().getStringList("clans.blocked-names")) {
            if (name.toLowerCase().contains(bad.toLowerCase()) || tag.toLowerCase().contains(bad.toLowerCase())) {
                Msg.send(p, "clan.blocked-name", "{prefix}&cDieser Name ist nicht erlaubt.");
                return;
            }
        }
        if (byName(name) != null || byName(tag) != null) { Msg.send(p, "clan.taken", "{prefix}&cName oder Tag ist bereits vergeben."); return; }
        double cost = cfg().getDouble("clans.create-cost", 10000);
        if (cost > 0 && !plugin.eco().withdraw(p, cost, "CLAN_CREATE", name)) {
            Msg.send(p, "clan.cost", "{prefix}&cEinen Clan zu gruenden kostet &f{cost}&c.", "cost", plugin.eco().format(cost));
            return;
        }
        Clan c = new Clan(UUID.randomUUID().toString(), name, tag.toUpperCase(Locale.ROOT));
        c.created = System.currentTimeMillis();
        c.members.put(p.getUniqueId(), Rank.LEADER);
        c.names.put(p.getUniqueId(), p.getName());
        clans.put(c.id, c);
        byMember.put(p.getUniqueId(), c);
        save(c);
        plugin.audit().log(p, "CLAN_CREATE", name + " [" + c.tag + "]");
        Msg.broadcast("clan.created", "{prefix}&f{player} &7hat den Clan &b{name} &8[&b{tag}&8] &7gegruendet!", "player", p.getName(), "name", name, "tag", c.tag);
    }

    private void invite(Player p, String[] a) {
        Clan c = require(p, Rank.MODERATOR);
        if (c == null) return;
        if (a.length < 2) { Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/clan invite <Spieler>"); return; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null || !p.canSee(t)) { Msg.send(p, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a[1]); return; }
        if (of(t) != null) { Msg.send(p, "clan.target-in-clan", "{prefix}&cDieser Spieler ist bereits in einem Clan."); return; }
        if (c.members.size() >= maxMembers(c)) { Msg.send(p, "clan.full", "{prefix}&cDein Clan ist voll ({max}). Levele ihn auf!", "max", String.valueOf(maxMembers(c))); return; }
        invites.computeIfAbsent(t.getUniqueId(), k -> new HashSet<>()).add(c.id);
        Msg.send(p, "clan.invited", "{prefix}&f{name} &7wurde eingeladen.", "name", t.getName());
        t.sendMessage(Text.c(Msg.get("clan.invite", "{prefix}&7Du wurdest in den Clan &b{clan} &7eingeladen. ", "clan", c.name))
                .append(Text.c("&a&l[BEITRETEN]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/clan join " + c.name))));
    }

    private void join(Player p, String[] a) {
        if (a.length < 2) { Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/clan join <Clan>"); return; }
        if (of(p) != null) { Msg.send(p, "clan.already", "{prefix}&cDu bist bereits in einem Clan."); return; }
        Clan c = byName(a[1]);
        if (c == null || !invites.getOrDefault(p.getUniqueId(), Set.of()).contains(c.id)) {
            Msg.send(p, "clan.no-invite", "{prefix}&cDu hast keine Einladung von diesem Clan.");
            return;
        }
        if (c.members.size() >= maxMembers(c)) { Msg.send(p, "clan.full", "{prefix}&cDein Clan ist voll ({max}). Levele ihn auf!", "max", String.valueOf(maxMembers(c))); return; }
        invites.remove(p.getUniqueId());
        c.members.put(p.getUniqueId(), Rank.MEMBER);
        c.names.put(p.getUniqueId(), p.getName());
        byMember.put(p.getUniqueId(), c);
        save(c);
        broadcastClan(c, Msg.get("clan.joined", "{prefix}&f{name} &aist dem Clan beigetreten!", "name", p.getName()));
        plugin.scoreboard().refreshNametags();
    }

    private void leave(Player p) {
        Clan c = of(p);
        if (c == null) { Msg.send(p, "clan.none", "{prefix}&cDu bist in keinem Clan."); return; }
        if (c.rank(p.getUniqueId()) == Rank.LEADER) {
            Msg.send(p, "clan.leader-leave", "{prefix}&cAls Leiter musst du den Clan uebertragen (&f/clan transfer&c) oder aufloesen (&f/clan disband&c).");
            return;
        }
        removeMember(c, p.getUniqueId());
        Msg.send(p, "clan.left", "{prefix}&7Du hast den Clan verlassen.");
        broadcastClan(c, Msg.get("clan.member-left", "{prefix}&f{name} &7hat den Clan verlassen.", "name", p.getName()));
    }

    private void removeMember(Clan c, UUID id) {
        c.members.remove(id);
        c.names.remove(id);
        byMember.remove(id);
        clanChatToggle.remove(id);
        save(c);
        plugin.scoreboard().refreshNametags();
    }

    private UUID memberByName(Clan c, String name) {
        for (var e : c.names.entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    private void kick(Player p, String[] a) {
        Clan c = require(p, Rank.MODERATOR);
        if (c == null || a.length < 2) return;
        UUID t = memberByName(c, a[1]);
        if (t == null) { Msg.send(p, "clan.not-member", "{prefix}&cDieser Spieler ist nicht in deinem Clan."); return; }
        if (!c.rank(p.getUniqueId()).atLeast(Rank.values()[Math.min(3, c.rank(t).ordinal() + 1)])) {
            Msg.send(p, "clan.cannot-kick", "{prefix}&cDu kannst nur niedrigere Raenge entfernen.");
            return;
        }
        String name = c.names.get(t);
        removeMember(c, t);
        broadcastClan(c, Msg.get("clan.kicked", "{prefix}&f{name} &7wurde aus dem Clan entfernt.", "name", name));
        Player tp = Bukkit.getPlayer(t);
        if (tp != null) Msg.send(tp, "clan.you-kicked", "{prefix}&cDu wurdest aus dem Clan &f{clan} &centfernt.", "clan", c.name);
    }

    private void changeRank(Player p, String[] a, boolean up) {
        Clan c = require(p, Rank.CO_LEADER);
        if (c == null || a.length < 2) return;
        UUID t = memberByName(c, a[1]);
        if (t == null || t.equals(p.getUniqueId())) { Msg.send(p, "clan.not-member", "{prefix}&cDieser Spieler ist nicht in deinem Clan."); return; }
        Rank cur = c.rank(t);
        Rank mine = c.rank(p.getUniqueId());
        int next = cur.ordinal() + (up ? 1 : -1);
        if (next < 0 || next >= Rank.LEADER.ordinal() || next >= mine.ordinal() || cur.ordinal() >= mine.ordinal()) {
            Msg.send(p, "clan.rank-invalid", "{prefix}&cDieser Rang kann nicht vergeben werden.");
            return;
        }
        c.members.put(t, Rank.values()[next]);
        save(c);
        broadcastClan(c, Msg.get("clan.rank-changed", "{prefix}&f{name} &7ist jetzt {rank}&7.", "name", c.names.get(t), "rank", Rank.values()[next].display));
    }

    private void transfer(Player p, String[] a) {
        Clan c = require(p, Rank.LEADER);
        if (c == null || a.length < 2) return;
        UUID t = memberByName(c, a[1]);
        if (t == null || t.equals(p.getUniqueId())) { Msg.send(p, "clan.not-member", "{prefix}&cDieser Spieler ist nicht in deinem Clan."); return; }
        ConfirmMenu.open(p, "&8Clan uebertragen?", Items.of(Material.GOLDEN_HELMET, "&6Neuer Leiter: &f" + c.names.get(t)), List.of("&cDu wirst Co-Leiter."), () -> {
            if (of(p) != c || c.rank(p.getUniqueId()) != Rank.LEADER || !c.members.containsKey(t)) return;
            c.members.put(t, Rank.LEADER);
            c.members.put(p.getUniqueId(), Rank.CO_LEADER);
            save(c);
            plugin.audit().log(p, "CLAN_TRANSFER", c.name + " -> " + c.names.get(t));
            broadcastClan(c, Msg.get("clan.transferred", "{prefix}&f{name} &7ist jetzt Leiter des Clans.", "name", c.names.get(t)));
        });
    }

    private void disband(Player p) {
        Clan c = require(p, Rank.LEADER);
        if (c == null) return;
        if (warOf(c) != null) { Msg.send(p, "clan.in-war", "{prefix}&cWaehrend eines Krieges nicht moeglich."); return; }
        ConfirmMenu.open(p, "&4Clan aufloesen?", Items.of(Material.TNT, "&c" + c.name + " aufloesen"),
                List.of("&7Das Clan-Bankguthaben (&a" + Text.money(c.bank) + "&7)", "&7geht an dich."), () -> {
                    if (!clans.containsKey(c.id) || c.rank(p.getUniqueId()) != Rank.LEADER) return;
                    if (c.bank > 0) plugin.eco().deposit(p, c.bank, "CLAN_DISBAND", c.name);
                    clans.remove(c.id);
                    for (UUID u : new ArrayList<>(c.members.keySet())) { byMember.remove(u); clanChatToggle.remove(u); }
                    for (Clan o : clans.values()) {
                        if (o.allies.remove(c.id) | o.rivals.remove(c.id)) save(o);
                    }
                    plugin.db().update("DELETE FROM clans WHERE id = ?", c.id);
                    plugin.audit().log(p, "CLAN_DISBAND", c.name);
                    plugin.scoreboard().refreshNametags();
                    Msg.broadcast("clan.disbanded", "{prefix}&7Der Clan &b{name} &7wurde aufgeloest.", "name", c.name);
                });
    }

    private void home(Player p) {
        Clan c = of(p);
        if (c == null) { Msg.send(p, "clan.none", "{prefix}&cDu bist in keinem Clan."); return; }
        Location l = PlayerData.locFromString(c.home);
        if (l == null) { Msg.send(p, "clan.no-home", "{prefix}&cDein Clan hat kein Home gesetzt."); return; }
        plugin.teleport().teleport(p, l, "Clan-Home");
    }

    private void setHome(Player p) {
        Clan c = require(p, Rank.CO_LEADER);
        if (c == null) return;
        if (!plugin.worlds().isSurvival(p.getWorld())) { Msg.send(p, "clan.home-world", "{prefix}&cClan-Homes nur in der Survival-Welt."); return; }
        c.home = PlayerData.locToString(p.getLocation());
        save(c);
        broadcastClan(c, Msg.get("clan.home-set", "{prefix}&7Das Clan-Home wurde von &f{name} &7gesetzt.", "name", p.getName()));
    }

    public boolean isClanChat(Player p) {
        return clanChatToggle.contains(p.getUniqueId());
    }

    public void chat(Player p, String msg) {
        Clan c = of(p);
        if (c == null) { Msg.send(p, "clan.none", "{prefix}&cDu bist in keinem Clan."); return; }
        if (msg.isBlank()) return;
        if (plugin.punish().mute(p) != null) { Msg.send(p, "mute.active", "{prefix}&cDu bist stummgeschaltet."); return; }
        String line = Msg.get("clan.chat-format", "&8[&bClan&8] {rank} &f{name}&8: &b{msg}", "rank", c.rank(p.getUniqueId()).display, "name", p.getName(), "msg", msg.replace("&", ""));
        broadcastClan(c, line);
        Bukkit.getConsoleSender().sendMessage("[Clan " + c.tag + "] " + p.getName() + ": " + msg);
    }

    private void top(Player p) {
        Msg.send(p, "clan.top-header", "&8&m-----&r &b&lClan-Rangliste &8&m-----");
        int i = 1;
        for (Clan c : top()) {
            if (i > 10) break;
            Msg.send(p, "clan.top-line", "&b#{rank} &f{name} &8[&b{tag}&8] &7Level &e{level} &8({members} Mitglieder)", "rank", String.valueOf(i++),
                    "name", c.name, "tag", c.tag, "level", String.valueOf(c.level), "members", String.valueOf(c.members.size()));
        }
    }

    private void info(Player p, Clan c) {
        if (c == null) { Msg.send(p, "clan.not-found", "{prefix}&cClan nicht gefunden."); return; }
        List<String> online = new ArrayList<>(), offline = new ArrayList<>();
        c.members.forEach((u, r) -> (Bukkit.getPlayer(u) != null ? online : offline).add(c.names.getOrDefault(u, "?")));
        Msg.send(p, "clan.info", "&8&m-----&r &b{name} &8[&b{tag}&8] &8&m-----\n&7Leiter: &f{leader}\n&7Level: &e{level} &8({xp}/{need} XP)\n"
                        + "&7Rang: &b#{pos}\n&7Mitglieder: &f{count}/{max}\n&7Online: &a{online}\n&7Offline: &7{offline}\n&7Bank: &a{bank}\n"
                        + "&7Kills: &c{kills} &8| &7Kriege: &a{won}W &c{lost}L\n&7Verbuendete: &f{allies}\n&7Rivalen: &c{rivals}",
                "name", c.name, "tag", c.tag, "leader", c.names.getOrDefault(c.leader(), "?"), "level", String.valueOf(c.level),
                "xp", Text.money(c.xp), "need", Text.money(xpForNext(c)), "pos", String.valueOf(position(c)),
                "count", String.valueOf(c.members.size()), "max", String.valueOf(maxMembers(c)),
                "online", online.isEmpty() ? "-" : String.join(", ", online), "offline", offline.isEmpty() ? "-" : String.join(", ", offline),
                "bank", plugin.eco().format(c.bank), "kills", String.valueOf(c.kills), "won", String.valueOf(c.warsWon), "lost", String.valueOf(c.warsLost),
                "allies", names(c.allies), "rivals", names(c.rivals));
    }

    private String names(Set<String> ids) {
        List<String> n = new ArrayList<>();
        for (String id : ids) { Clan o = clans.get(id); if (o != null) n.add(o.name); }
        return n.isEmpty() ? "-" : String.join(", ", n);
    }

    private void bank(Player p, String[] a) {
        Clan c = of(p);
        if (c == null) { Msg.send(p, "clan.none", "{prefix}&cDu bist in keinem Clan."); return; }
        if (a.length < 3) {
            Msg.send(p, "clan.bank", "{prefix}&7Clan-Bank: &a{amount} &8| &b/clan bank deposit|withdraw <Betrag>", "amount", plugin.eco().format(c.bank));
            return;
        }
        double amount = Text.parseAmount(a[2]);
        if (amount <= 0) { Msg.send(p, "pay.invalid-amount", "{prefix}&cUngueltiger Betrag."); return; }
        if (a[1].equalsIgnoreCase("deposit") || a[1].equalsIgnoreCase("einzahlen")) {
            if (!plugin.eco().withdraw(p, amount, "CLAN_DEPOSIT", c.name)) { Msg.send(p, "not-enough-money", "{prefix}&cDu hast nicht genug MineCoins."); return; }
            c.bank += amount;
            addXp(c, amount / 1000.0 * cfg().getDouble("clans.xp.per-1000-deposit", 5));
            save(c);
            broadcastClan(c, Msg.get("clan.deposited", "{prefix}&f{name} &7hat &a{amount} &7in die Clan-Bank eingezahlt.", "name", p.getName(), "amount", plugin.eco().format(amount)));
        } else if (a[1].equalsIgnoreCase("withdraw") || a[1].equalsIgnoreCase("abheben")) {
            if (!c.rank(p.getUniqueId()).atLeast(Rank.CO_LEADER)) { Msg.send(p, "clan.rank-too-low", "{prefix}&cDafuer brauchst du mindestens den Rang {rank}&c.", "rank", Rank.CO_LEADER.display); return; }
            if (warOf(c) != null) { Msg.send(p, "clan.in-war", "{prefix}&cWaehrend eines Krieges nicht moeglich."); return; }
            if (c.bank + 1e-9 < amount) { Msg.send(p, "clan.bank-empty", "{prefix}&cNicht genug Geld in der Clan-Bank."); return; }
            c.bank -= amount;
            save(c);
            plugin.eco().deposit(p, amount, "CLAN_WITHDRAW", c.name);
            plugin.audit().log(p, "CLAN_WITHDRAW", c.name + " " + amount);
            broadcastClan(c, Msg.get("clan.withdrew", "{prefix}&f{name} &7hat &c{amount} &7aus der Clan-Bank abgehoben.", "name", p.getName(), "amount", plugin.eco().format(amount)));
        }
    }

    private void ally(Player p, String[] a) {
        Clan c = require(p, Rank.CO_LEADER);
        if (c == null || a.length < 2) return;
        Clan o = byName(a[1]);
        if (o == null || o == c) { Msg.send(p, "clan.not-found", "{prefix}&cClan nicht gefunden."); return; }
        if (c.allies.contains(o.id)) { Msg.send(p, "clan.already-ally", "{prefix}&cIhr seid bereits verbuendet."); return; }
        if (c.allies.size() >= cfg().getInt("clans.max-allies", 3)) { Msg.send(p, "clan.max-allies", "{prefix}&cMaximale Anzahl an Verbuendeten erreicht."); return; }
        if (c.id.equals(allyRequests.get(o.id)) ) { Msg.send(p, "clan.ally-pending", "{prefix}&7Anfrage bereits gesendet."); return; }
        if (o.id.equals(allyRequests.get(c.id))) {
            allyRequests.remove(c.id);
            c.allies.add(o.id); o.allies.add(c.id);
            c.rivals.remove(o.id); o.rivals.remove(c.id);
            save(c); save(o);
            String m = Msg.get("clan.allied", "{prefix}&aDie Clans &f{a} &aund &f{b} &asind jetzt verbuendet!", "a", c.name, "b", o.name);
            broadcastClan(c, m); broadcastClan(o, m);
            return;
        }
        allyRequests.put(o.id, c.id);
        Msg.send(p, "clan.ally-sent", "{prefix}&7Buendnisanfrage an &b{name} &7gesendet.", "name", o.name);
        broadcastClan(o, Msg.get("clan.ally-request", "{prefix}&b{name} &7moechte ein Buendnis. Leitung: &f/clan ally {name}", "name", c.name));
    }

    private void unally(Player p, String[] a) {
        Clan c = require(p, Rank.CO_LEADER);
        if (c == null || a.length < 2) return;
        Clan o = byName(a[1]);
        if (o == null || !c.allies.contains(o.id)) { Msg.send(p, "clan.not-ally", "{prefix}&cIhr seid nicht verbuendet."); return; }
        c.allies.remove(o.id); o.allies.remove(c.id);
        save(c); save(o);
        String m = Msg.get("clan.unallied", "{prefix}&7Das Buendnis zwischen &f{a} &7und &f{b} &7wurde beendet.", "a", c.name, "b", o.name);
        broadcastClan(c, m); broadcastClan(o, m);
    }

    private void rival(Player p, String[] a, boolean add) {
        Clan c = require(p, Rank.CO_LEADER);
        if (c == null || a.length < 2) return;
        Clan o = byName(a[1]);
        if (o == null || o == c) { Msg.send(p, "clan.not-found", "{prefix}&cClan nicht gefunden."); return; }
        if (add) {
            c.allies.remove(o.id); o.allies.remove(c.id);
            c.rivals.add(o.id);
            save(o);
            broadcastClan(o, Msg.get("clan.rival-declared", "{prefix}&c{name} &7hat euch zu Rivalen erklaert!", "name", c.name));
        } else c.rivals.remove(o.id);
        save(c);
        Msg.send(p, "clan.rival-updated", "{prefix}&7Rivalitaet aktualisiert.");
    }

    // ---------------- Kriege ----------------

    public War warOf(Clan c) {
        for (War w : wars) if (w.a == c || w.b == c) return w;
        return null;
    }

    private int online(Clan c) {
        int n = 0;
        for (UUID u : c.members.keySet()) if (Bukkit.getPlayer(u) != null) n++;
        return n;
    }

    private void war(Player p, String[] a) {
        Clan c = require(p, Rank.LEADER);
        if (c == null) return;
        if (!cfg().getBoolean("clans.war.enabled", true)) { Msg.send(p, "clan.war-disabled", "{prefix}&cClan-Kriege sind deaktiviert."); return; }
        if (a.length >= 2 && a[1].equalsIgnoreCase("accept")) {
            WarRequest r = warRequests.remove(c.id);
            Clan o = r == null ? null : clans.get(r.fromClan);
            if (o == null || System.currentTimeMillis() - r.time > 120_000) { Msg.send(p, "clan.no-war-request", "{prefix}&cKeine offene Kriegserklaerung."); return; }
            startWar(o, c, r.stake, p);
            return;
        }
        if (a.length < 3) { Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/clan war <Clan> <Einsatz> | /clan war accept"); return; }
        Clan o = byName(a[1]);
        double stake = Text.parseAmount(a[2]);
        if (o == null || o == c) { Msg.send(p, "clan.not-found", "{prefix}&cClan nicht gefunden."); return; }
        if (c.allies.contains(o.id)) { Msg.send(p, "clan.war-ally", "{prefix}&cGegen Verbuendete kann kein Krieg gefuehrt werden."); return; }
        double min = cfg().getDouble("clans.war.min-stake", 1000), max = cfg().getDouble("clans.war.max-stake", 1_000_000);
        if (stake < min || stake > max) { Msg.send(p, "clan.war-stake", "{prefix}&cEinsatz zwischen &f{min} &cund &f{max}&c.", "min", Text.money(min), "max", Text.money(max)); return; }
        if (warOf(c) != null || warOf(o) != null) { Msg.send(p, "clan.in-war", "{prefix}&cWaehrend eines Krieges nicht moeglich."); return; }
        long cd = warCooldown.getOrDefault(c.id, 0L) - System.currentTimeMillis();
        if (cd > 0) { Msg.send(p, "cooldown", "{prefix}&cBitte warte noch &f{time}&c.", "time", TimeUtil.format(cd)); return; }
        if (c.bank < stake) { Msg.send(p, "clan.bank-empty", "{prefix}&cNicht genug Geld in der Clan-Bank."); return; }
        warRequests.put(o.id, new WarRequest(c.id, stake, System.currentTimeMillis()));
        Msg.send(p, "clan.war-sent", "{prefix}&7Kriegserklaerung an &c{name} &7gesendet (Einsatz {stake}).", "name", o.name, "stake", plugin.eco().format(stake));
        broadcastClan(o, Msg.get("clan.war-request", "{prefix}&c&lKRIEG! &f{name} &7erklaert euch den Krieg. Einsatz: &a{stake}&7. Leiter: &f/clan war accept",
                "name", c.name, "stake", plugin.eco().format(stake)));
    }

    private void startWar(Clan a, Clan b, double stake, Player acceptor) {
        int minOnline = cfg().getInt("clans.war.min-online", 2);
        if (online(a) < minOnline || online(b) < minOnline) {
            Msg.send(acceptor, "clan.war-min-online", "{prefix}&cBeide Clans brauchen mindestens {n} Spieler online.", "n", String.valueOf(minOnline));
            return;
        }
        if (a.bank < stake || b.bank < stake) { Msg.send(acceptor, "clan.bank-empty", "{prefix}&cNicht genug Geld in der Clan-Bank."); return; }
        if (warOf(a) != null || warOf(b) != null) return;
        a.bank -= stake; b.bank -= stake;
        save(a); save(b);
        long end = System.currentTimeMillis() + cfg().getLong("clans.war.duration-minutes", 30) * 60_000;
        War w = new War(a, b, end, stake * 2);
        wars.add(w);
        Msg.broadcast("clan.war-start", "{prefix}&4&lCLAN-KRIEG! &f{a} &7vs &f{b} &7- Pot: &a{pot} &7- Dauer: &f{min} Min.",
                "a", a.name, "b", b.name, "pot", plugin.eco().format(w.pot), "min", String.valueOf(cfg().getLong("clans.war.duration-minutes", 30)));
        plugin.webhook().send("events", "Clan-Krieg gestartet", a.name + " vs " + b.name + " - Pot: " + plugin.eco().format(w.pot), 0xC62828);
    }

    private void tickWars() {
        long now = System.currentTimeMillis();
        for (War w : new ArrayList<>(wars)) {
            long left = w.end - now;
            for (Clan c : List.of(w.a, w.b)) {
                for (UUID u : c.members.keySet()) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) plugin.scoreboard().bossbar(p, "war", Text.replace("&c&lKRIEG &f{a} &c{ka} &7: &c{kb} &f{b} &8| &7{time}",
                            "a", w.a.tag, "b", w.b.tag, "ka", String.valueOf(w.killsA), "kb", String.valueOf(w.killsB), "time", TimeUtil.format(Math.max(0, left))),
                            Math.max(0, Math.min(1, left / (double) (cfg().getLong("clans.war.duration-minutes", 30) * 60_000))), BarColor.RED);
                }
            }
            if (left <= 0) endWar(w);
        }
    }

    private void endWar(War w) {
        wars.remove(w);
        for (Clan c : List.of(w.a, w.b)) {
            for (UUID u : c.members.keySet()) { Player p = Bukkit.getPlayer(u); if (p != null) plugin.scoreboard().hideBossbar(p, "war"); }
            warCooldown.put(c.id, System.currentTimeMillis() + cfg().getLong("clans.war.cooldown-hours", 6) * 3_600_000);
        }
        if (w.killsA == w.killsB) {
            w.a.bank += w.pot / 2; w.b.bank += w.pot / 2;
            w.a.seasonDraws++; w.b.seasonDraws++;
            Msg.broadcast("clan.war-draw", "{prefix}&7Der Krieg &f{a} &7vs &f{b} &7endet unentschieden ({k}:{k}). Einsaetze zurueck.", "a", w.a.name, "b", w.b.name, "k", String.valueOf(w.killsA));
        } else {
            Clan win = w.killsA > w.killsB ? w.a : w.b, lose = win == w.a ? w.b : w.a;
            win.bank += w.pot;
            win.warsWon++; lose.warsLost++;
            win.seasonWins++; lose.seasonLosses++;
            addXp(win, cfg().getDouble("clans.xp.war-win", 500));
            Msg.broadcast("clan.war-end", "{prefix}&6&lSIEG! &f{win} &7gewinnt den Krieg gegen &f{lose} &7({ka}:{kb}) und erhaelt &a{pot}&7!",
                    "win", win.name, "lose", lose.name, "ka", String.valueOf(Math.max(w.killsA, w.killsB)), "kb", String.valueOf(Math.min(w.killsA, w.killsB)), "pot", plugin.eco().format(w.pot));
        }
        save(w.a); save(w.b);
    }

    /** Wird vom StatsListener bei PvP-Kills aufgerufen. */
    public void onKill(Player killer, Player victim) {
        Clan kc = of(killer), vc = of(victim);
        if (kc == null) return;
        if (vc != null && (vc == kc || kc.allies.contains(vc.id))) return;
        kc.kills++;
        addXp(kc, cfg().getDouble("clans.xp.kill", 10) * (vc != null && kc.rivals.contains(vc.id) ? 2 : 1));
        War w = warOf(kc);
        if (w != null && vc != null && (w.a == vc || w.b == vc)) {
            if (w.a == kc) w.killsA++; else w.killsB++;
        }
    }

    // Friendly Fire / Allianz-Schutz
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = e.getDamager() instanceof Player p ? p
                : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p2 ? p2 : null;
        if (attacker == null || attacker.equals(victim)) return;
        Clan a = of(attacker), b = of(victim);
        if (a == null || b == null) return;
        if (plugin.duels().inDuel(attacker)) return;
        if ((a == b && !a.friendlyFire) || (a != b && a.allies.contains(b.id) && !cfg().getBoolean("clans.ally-damage", false))) e.setCancelled(true);
    }

    // ---------------- GUI ----------------

    public void openMenu(Player p) {
        Clan c = of(p);
        if (c == null) {
            Menu m = new Menu(3, "&8Clans") {};
            m.set(11, Items.of(Material.WHITE_BANNER, 1, "&bClan gruenden", List.of("&7/clan create <Name> <Tag>", "&7Kosten: &a" + plugin.eco().format(cfg().getDouble("clans.create-cost", 10000)))));
            m.set(13, Items.of(Material.GOLDEN_HELMET, 1, "&eRangliste", List.of("&7Die besten Clans")), e -> { Menu.close(p); top(p); });
            m.set(15, Items.of(Material.BOOK, 1, "&7Hilfe", List.of("&7Alle Befehle")), e -> { Menu.close(p); help(p); });
            m.fillEmpty();
            m.open(p);
            return;
        }
        Menu m = new Menu(6, "&8Clan: &b" + c.name + " &8[" + c.tag + "]") {};
        m.set(4, Items.of(Material.BLUE_BANNER, 1, "&b&l" + c.name + " &8[&b" + c.tag + "&8]", List.of(
                "&7Level: &e" + c.level, "&7XP: &f" + Text.money(c.xp) + "&7/&f" + Text.money(xpForNext(c)),
                Text.progressBar(c.xp / xpForNext(c), 20, "&b", "&8"), "&7Rang: &b#" + position(c),
                "&7Mitglieder: &f" + c.members.size() + "/" + maxMembers(c), "&7Kills: &c" + c.kills)));
        int slot = 18;
        for (var e : c.members.entrySet()) {
            if (slot > 44) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            boolean on = op.isOnline();
            m.set(slot++, Items.head(op, (on ? "&a● " : "&7● ") + "&f" + c.names.getOrDefault(e.getKey(), "?"), List.of("&7Rang: " + e.getValue().display)));
        }
        m.set(45, Items.of(Material.GOLD_BLOCK, 1, "&6Clan-Bank", List.of("&a" + plugin.eco().format(c.bank), "", "&7/clan bank deposit <Betrag>")));
        m.set(46, Items.of(Material.RED_BED, 1, "&bClan-Home", List.of("&7Klicke zum Teleportieren")), e -> { Menu.close(p); home(p); });
        m.set(47, Items.of(Material.WRITABLE_BOOK, 1, "&bClanchat", List.of("&7Status: " + (isClanChat(p) ? "&aan" : "&caus"), "&7Klicke zum Umschalten")), e -> {
            if (!clanChatToggle.remove(p.getUniqueId())) clanChatToggle.add(p.getUniqueId());
            openMenu(p);
        });
        m.set(49, Items.of(Material.GOLDEN_HELMET, "&eRangliste"), e -> { Menu.close(p); top(p); });
        m.set(51, Items.of(Material.SHIELD, 1, "&aVerbuendete", List.of("&f" + names(c.allies))));
        m.set(52, Items.of(Material.IRON_SWORD, 1, "&cRivalen", List.of("&f" + names(c.rivals))));
        m.set(53, Items.of(Material.OAK_DOOR, 1, "&cClan verlassen", List.of("&7/clan leave")), e -> { Menu.close(p); leave(p); });
        m.fillEmpty();
        m.open(p);
    }
}
