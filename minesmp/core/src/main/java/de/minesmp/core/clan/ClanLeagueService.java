package de.minesmp.core.clan;

import de.minesmp.core.MineSMP;
import de.minesmp.core.combat.KitService.Kit;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import de.minesmp.core.utility.RewardService;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Clan-Liga: gewertete Clan-Duelle (Standard 3 gegen 3) mit Wertung (Elo), 11 Raengen, Platzierungsmatches,
 * Aufstellung, Match-Suche und Saison-Rangliste. Befehl: /clanduell (Menue), Rangliste am Spawn (ClanBoardService).
 * Das eigene Inventar wird wie bei Duellen gesichert und danach wiederhergestellt.
 */
public final class ClanLeagueService implements Listener {
    public record LeagueRank(int rating, String name, String color, String icon, Material material) {
        public String display() { return color + name; }
    }

    private enum Phase { COUNTDOWN, FIGHT, ENDED }

    private final class Match {
        final Clan a, b;
        final List<Player> ta, tb;
        final Set<UUID> alive = new HashSet<>();
        final String arena;
        final long start = System.currentTimeMillis();
        Phase phase = Phase.COUNTDOWN;
        Match(Clan a, Clan b, List<Player> ta, List<Player> tb, String arena) { this.a = a; this.b = b; this.ta = ta; this.tb = tb; this.arena = arena; }
        boolean teamA(Player p) { return ta.contains(p); }
        int aliveOf(List<Player> team) { int n = 0; for (Player p : team) if (alive.contains(p.getUniqueId())) n++; return n; }
        List<Player> all() { List<Player> l = new ArrayList<>(ta); l.addAll(tb); return l; }
    }

    private final MineSMP plugin;
    private final List<LeagueRank> ranks = new ArrayList<>();
    private final Map<String, Long> queue = new LinkedHashMap<>();
    private final Map<UUID, Match> matches = new HashMap<>();
    private final Set<String> busyArenas = new HashSet<>();
    private final Map<String, Long> lastPairing = new HashMap<>();
    private final int[] activity = new int[24];
    private final List<String> previousSeason = new ArrayList<>();
    private String previousSeasonName = "";

    public ClanLeagueService(MineSMP plugin) {
        this.plugin = plugin;
        reload();
        plugin.listen(this);
        plugin.command(new SimpleCommand("clanduell", "minesmp.clan", "Clan-Liga: gewertete Clan-Duelle", this::cmd,
                (s, a) -> a.length == 1 ? List.of("aufstellung", "suchen", "stop", "rangliste", "raenge") : List.of(), "cd", "liga", "clanduel", "clanliga"));
        plugin.db().kvGet("league-activity").thenAccept(raw -> {
            if (raw == null) return;
            String[] p = raw.split(",");
            for (int i = 0; i < Math.min(24, p.length); i++) try { activity[i] = Integer.parseInt(p[i]); } catch (NumberFormatException ignored) { }
        });
        plugin.db().kvGet("league-previous").thenAccept(raw -> {
            if (raw == null || raw.isBlank()) return;
            String[] lines = raw.split("\n");
            plugin.sync(() -> {
                previousSeasonName = lines[0];
                for (int i = 1; i < lines.length; i++) previousSeason.add(lines[i]);
            });
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::second, 20, 20);
    }

    public void reload() {
        ranks.clear();
        for (Map<?, ?> m : plugin.getConfig().getMapList("clans.league.ranks")) {
            Material mat = Material.matchMaterial(String.valueOf(m.get("material")));
            ranks.add(new LeagueRank(((Number) m.get("rating")).intValue(), String.valueOf(m.get("name")), String.valueOf(m.get("color")),
                    m.get("icon") == null ? "" : String.valueOf(m.get("icon")), mat == null ? Material.IRON_INGOT : mat));
        }
        if (ranks.isEmpty()) ranks.add(new LeagueRank(0, "Bronze I", "&6", "", Material.COPPER_INGOT));
        ranks.sort(Comparator.comparingInt(LeagueRank::rating));
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("clans.league");
        return s == null ? new org.bukkit.configuration.MemoryConfiguration() : s;
    }

    public boolean enabled() { return cfg().getBoolean("enabled", true); }
    public int teamSize() { return Math.max(1, cfg().getInt("team-size", 3)); }
    public int placement() { return Math.max(0, cfg().getInt("placement-matches", 5)); }
    public List<LeagueRank> ranks() { return ranks; }

    /** Rang eines Clans oder null, solange die Platzierungsmatches nicht gespielt sind. */
    public LeagueRank rankOf(Clan c) {
        if (c.leagueMatches < placement()) return null;
        return rankFor(c.rating);
    }

    public LeagueRank rankFor(int rating) {
        LeagueRank best = ranks.get(0);
        for (LeagueRank r : ranks) if (rating >= r.rating) best = r;
        return best;
    }

    /** Rang als Text; mit Ressourcenpaket steht das Rang-Symbol davor. */
    public String rankText(Clan c, boolean pack) {
        LeagueRank r = rankOf(c);
        if (r == null) return "&8Unplatziert";
        return (pack && !r.icon.isEmpty() && plugin.pack() != null ? "&f" + plugin.pack().glyphs(":" + r.icon + ":") + " " : "") + r.display();
    }

    /** Saison-Rangliste: platzierte Clans nach Wertung, danach die uebrigen nach gespielten Matches. */
    public List<Clan> ranking() {
        List<Clan> list = new ArrayList<>(plugin.clans().all());
        int pl = placement();
        list.sort((x, y) -> {
            boolean rx = x.leagueMatches >= pl, ry = y.leagueMatches >= pl;
            if (rx != ry) return rx ? -1 : 1;
            if (rx && x.rating != y.rating) return y.rating - x.rating;
            if (x.leagueMatches != y.leagueMatches) return y.leagueMatches - x.leagueMatches;
            if (x.seasonWins != y.seasonWins) return y.seasonWins - x.seasonWins;
            return y.level - x.level;
        });
        return list;
    }

    public boolean inMatch(Player p) { return matches.containsKey(p.getUniqueId()); }

    private String seasonName() { return plugin.getConfig().getString("clans.season.name", "Saison 1"); }

    private String seasonEnds() {
        try {
            LocalDateTime end = LocalDateTime.parse(plugin.getConfig().getString("clans.season.end", ""), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            long ms = Duration.between(LocalDateTime.now(RewardService.ZONE), end).toMillis();
            return ms <= 0 ? "beendet" : "endet in " + Math.max(1, ms / 86_400_000L) + " Tagen";
        } catch (Exception e) {
            return "";
        }
    }

    // ================================================================ Befehl
    private void cmd(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        if (!enabled()) { Msg.send(p, "league.disabled", "{prefix}&cDie Clan-Liga ist deaktiviert."); return; }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "rangliste", "top" -> openLeaderboard(p, false);
            case "raenge", "ränge", "ranks" -> openRanks(p);
            case "aufstellung", "lineup" -> { if (clan(p) != null) openLineup(p, 0); }
            case "suchen", "search", "queue" -> { Clan c = clan(p); if (c != null) toggleQueue(p, c, true); }
            case "stop" -> { Clan c = clan(p); if (c != null && queue.remove(c.id) != null) plugin.clans().broadcastClan(c, Msg.get("league.queue-stop", "{prefix}&7Die Match-Suche wurde beendet.")); }
            default -> openMain(p);
        }
    }

    private Clan clan(Player p) {
        Clan c = plugin.clans().of(p);
        if (c == null) Msg.send(p, "clan.none", "{prefix}&cDu bist in keinem Clan.");
        return c;
    }

    private boolean mayManage(Player p, Clan c) {
        Clan.Rank r = c.rank(p.getUniqueId());
        return r != null && r.atLeast(Clan.Rank.MODERATOR);
    }

    private List<UUID> lineup(Clan c) {
        List<UUID> out = new ArrayList<>();
        for (String s : c.lineup) {
            try {
                UUID id = UUID.fromString(s);
                if (c.members.containsKey(id) && !out.contains(id) && out.size() < teamSize()) out.add(id);
            } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    private boolean ready(Player p) {
        return p != null && p.isOnline() && !p.isDead() && !plugin.duels().inDuel(p) && !plugin.combat().isTagged(p);
    }

    private int readyCount(Clan c) {
        int n = 0;
        for (UUID id : lineup(c)) if (ready(Bukkit.getPlayer(id))) n++;
        return n;
    }

    // ================================================================ Menues
    public void openMain(Player p) {
        Clan c = plugin.clans().of(p);
        boolean pack = plugin.pack() != null && plugin.pack().has(p);
        Menu m = new Menu(6, "&8Clan-Duell &7· " + seasonName()) {};
        List<String> info = new ArrayList<>(List.of("&7" + seasonName() + " &8· &7" + seasonEnds(), "&8"));
        if (c == null) info.add("&cDu bist in keinem Clan. &7/clan create");
        else {
            info.add("&7Clan: &b" + c.name + " &8[&b" + c.tag + "&8]");
            info.add("&7Rang: " + rankText(c, pack));
            info.add("&7Wertung: &f" + (c.leagueMatches >= placement() ? String.valueOf(c.rating) : "&8nach der Platzierung"));
            info.add("&7Bilanz: &a" + c.seasonWins + " S &8· &e" + c.seasonDraws + " U &8· &c" + c.seasonLosses + " N");
        }
        m.set(4, Items.glow(Items.of(Material.NETHER_STAR, 1, "&b&lClan-Duell", info)));
        if (c != null) {
            // Platzierung bzw. letzte Matches
            boolean placing = c.leagueMatches < placement();
            int n = Math.max(1, Math.min(7, placing ? placement() : 5));
            int first = 13 - n / 2;
            List<String> hist = c.history;
            for (int i = 0; i < n; i++) {
                int idx = placing ? i : hist.size() - n + i;
                String[] h = idx >= 0 && idx < hist.size() ? hist.get(idx).split("\\|") : null;
                Material mat = h == null ? Material.GRAY_STAINED_GLASS_PANE : h[0].equals("W") ? Material.LIME_STAINED_GLASS_PANE : h[0].equals("L") ? Material.RED_STAINED_GLASS_PANE : Material.YELLOW_STAINED_GLASS_PANE;
                List<String> lore = h == null ? List.of("&8Noch nicht gespielt") : List.of((h[0].equals("W") ? "&aSieg" : h[0].equals("L") ? "&cNiederlage" : "&eUnentschieden") + " &7gegen &f[" + h[2] + "]", "&7Wertung: &f" + h[1]);
                m.set(first + i, Items.of(mat, 1, (placing ? "&dPlatzierung &7· Match " : "&7Match ") + (i + 1), lore));
            }
            m.set(first - 1 >= 9 ? first - 1 : 9, Items.of(placing ? Material.COMPASS : Material.BOOK, 1, placing ? "&d&lPLATZIERUNG" : "&b&lLetzte Matches",
                    placing ? List.of("&f" + c.leagueMatches + " von " + placement() + " Matches gespielt", "&7Danach bekommt ihr euren Rang.") : List.of("&7Die letzten " + n + " gewerteten Matches")));
            // Aufstellung
            List<UUID> lu = lineup(c);
            int size = teamSize();
            int start = 31 - Math.min(size, 7) / 2;
            for (int i = 0; i < Math.min(size, 7); i++) {
                UUID id = i < lu.size() ? lu.get(i) : null;
                Player on = id == null ? null : Bukkit.getPlayer(id);
                ItemStack it = id == null ? Items.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, 1, "&7Platz " + (i + 1), List.of("&dSpieler waehlen ›"))
                        : Items.head(Bukkit.getOfflinePlayer(id), "&f" + c.names.getOrDefault(id, "?"), List.of(ready(on) ? "&a✔ bereit" : on == null ? "&cnicht online" : "&enicht bereit (im Kampf)", "&7Platz " + (i + 1)));
                int slot = i;
                m.set(start + i, it, e -> openLineup(p, slot));
            }
            m.set(start - 1, Items.of(Material.ARMOR_STAND, 1, "&b&lAufstellung", List.of("&f" + size + " gegen " + size + " &8· &f" + readyCount(c) + " von " + size + " bereit", "&7Klicke, um die Aufstellung zu aendern.")), e -> openLineup(p, 0));
            boolean queued = queue.containsKey(c.id);
            boolean full = readyCount(c) >= size;
            m.set(40, Items.glow(Items.of(queued ? Material.ENDER_EYE : full ? Material.ENDER_PEARL : Material.BARRIER, 1,
                    queued ? "&a&lSuche laeuft ..." : "&d&l🔍 Match suchen", queued
                            ? List.of("&7Wartet seit &f" + TimeUtil.format(System.currentTimeMillis() - queue.get(c.id)), "&7Clans in der Suche: &f" + queue.size(), "&8", "&cKlicke zum Abbrechen")
                            : List.of("&7gewertet &8· &f" + size + " gegen " + size, full ? "&aStartet die Suche nach einem gewerteten Match" : "&cErst " + size + " bereite Spieler aufstellen"))), e -> {
                toggleQueue(p, c, false);
                openMain(p);
            });
        }
        m.set(46, Items.of(Material.CLOCK, 1, "&b&lWann suchen andere Clans?", activityLore()));
        m.set(48, Items.of(Material.AMETHYST_SHARD, 1, "&d&lMoegliche Raenge", List.of("&7Alle " + ranks.size() + " Raenge ›", "&8Wie gut die Platzierung laeuft,", "&8entscheidet euren Startrang.")), e -> openRanks(p));
        m.set(50, Items.of(Material.GOLDEN_HELMET, 1, "&e&lRangliste", List.of("&7" + seasonName() + " &8· &7die besten Clans ›")), e -> openLeaderboard(p, false));
        m.set(52, Items.of(Material.BOOK, 1, "&f&lSo funktioniert's", List.of("&71. Aufstellung waehlen (" + teamSize() + " Spieler)", "&72. Match suchen", "&73. In der Clan-Arena gewinnen",
                "&7Nach " + placement() + " Matches bekommt ihr einen Rang.", "&7Euer Inventar wird gesichert, gekaempft", "&7wird mit einem festen Kit.")));
        m.fillEmpty();
        m.open(p);
    }

    private List<String> activityLore() {
        int max = 1;
        for (int v : activity) max = Math.max(max, v);
        String bars = " ▁▂▃▄▅▆▇█";
        StringBuilder sb = new StringBuilder("&3");
        for (int h = 0; h < 24; h++) sb.append(activity[h] == 0 ? "&8▁&3" : String.valueOf(bars.charAt(1 + (int) Math.round(activity[h] * 7.0 / max))));
        int bestStart = 0, bestSum = -1;
        for (int h = 0; h < 24; h++) {
            int sum = activity[h] + activity[(h + 1) % 24] + activity[(h + 2) % 24];
            if (sum > bestSum) { bestSum = sum; bestStart = h; }
        }
        int now = LocalDateTime.now(RewardService.ZONE).getHour();
        List<String> lore = new ArrayList<>(List.of(sb.toString(), "&800      06      12      18    23"));
        lore.add(bestSum <= 0 ? "&7Noch keine Daten - sei der Erste!" : "&7Am meisten los ist zwischen &f" + bestStart + " &7und &f" + ((bestStart + 3) % 24) + " Uhr&7.");
        lore.add("&7Jetzt: &f" + now + " Uhr &8· &7in der Suche: &f" + queue.size());
        return lore;
    }

    public void openLineup(Player p, int selected) {
        Clan c = plugin.clans().of(p);
        if (c == null) return;
        int size = teamSize();
        int sel = Math.max(0, Math.min(size - 1, selected));
        boolean manage = mayManage(p, c);
        List<UUID> lu = lineup(c);
        Menu m = new Menu(6, "&8Aufstellung &7· " + size + " gegen " + size + " · " + readyCount(c) + " von " + size) {};
        int start = 4 - Math.min(size, 7) / 2;
        for (int i = 0; i < Math.min(size, 7); i++) {
            UUID id = i < lu.size() ? lu.get(i) : null;
            Player on = id == null ? null : Bukkit.getPlayer(id);
            List<String> lore = new ArrayList<>();
            if (id == null) lore.add("&dSpieler waehlen ›");
            else lore.add(ready(on) ? "&a✔ bereit" : on == null ? "&cnicht online" : "&enicht bereit");
            if (i == sel) lore.add("&b▶ Ausgewaehlt - unten Spieler anklicken");
            if (id != null && manage) lore.add("&8Shift-Klick: Platz leeren");
            ItemStack it = id == null ? Items.of(i == sel ? Material.MAGENTA_STAINED_GLASS_PANE : Material.LIGHT_GRAY_STAINED_GLASS_PANE, 1, "&fPlatz " + (i + 1), lore)
                    : Items.head(Bukkit.getOfflinePlayer(id), (i == sel ? "&d" : "&f") + c.names.getOrDefault(id, "?") + " &8· &7Platz " + (i + 1), lore);
            if (i == sel) Items.glow(it);
            int slot = i;
            m.set(start + i, it, e -> {
                if (e.isShiftClick() && manage && slot < lineup(c).size()) {
                    List<UUID> cur = lineup(c);
                    cur.remove(slot);
                    setLineup(c, cur);
                }
                openLineup(p, slot);
            });
        }
        m.set(9, Items.of(Material.NAME_TAG, 1, "&7FUER PLATZ " + (sel + 1) + " WAEHLEN", List.of(manage ? "&7Klicke auf ein Clan-Mitglied." : "&cNur Moderatoren und hoeher duerfen aufstellen.")));
        int slot = 18;
        List<Map.Entry<UUID, Clan.Rank>> members = new ArrayList<>(c.members.entrySet());
        members.sort((x, y) -> Boolean.compare(Bukkit.getPlayer(y.getKey()) != null, Bukkit.getPlayer(x.getKey()) != null));
        for (Map.Entry<UUID, Clan.Rank> en : members) {
            if (slot > 44) break;
            UUID id = en.getKey();
            Player on = Bukkit.getPlayer(id);
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            boolean in = lu.contains(id);
            List<String> lore = new ArrayList<>(List.of(on != null ? (ready(on) ? "&a● bereit" : "&e● im Kampf") : "&8● nicht online"));
            if (in) lore.add("&bSteht auf Platz " + (lu.indexOf(id) + 1));
            else if (manage && on != null) lore.add("&dAufstellen › &8(Platz " + (sel + 1) + ")");
            String name = c.names.getOrDefault(id, "?");
            m.set(slot++, Items.head(op, (on != null ? "&f" : "&8") + name, lore), e -> {
                if (!manage || on == null || !on.isOnline()) return;
                List<UUID> cur = lineup(c);
                cur.remove(id);
                if (sel < cur.size()) cur.set(sel, id); else cur.add(id);
                setLineup(c, cur);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
                openLineup(p, Math.min(size - 1, sel + 1));
            });
        }
        m.set(49, Items.of(Material.ARROW, "&7Zurueck zum Clan-Duell"), e -> openMain(p));
        m.fillEmpty();
        m.open(p);
    }

    private void setLineup(Clan c, List<UUID> ids) {
        c.lineup.clear();
        for (UUID id : ids) c.lineup.add(id.toString());
        plugin.clans().save(c);
        if (queue.remove(c.id) != null) plugin.clans().broadcastClan(c, Msg.get("league.queue-lineup", "{prefix}&7Aufstellung geaendert - die Match-Suche wurde beendet."));
    }

    public void openRanks(Player p) {
        boolean pack = plugin.pack() != null && plugin.pack().has(p);
        Clan c = plugin.clans().of(p);
        LeagueRank mine = c == null ? null : rankOf(c);
        Menu m = new Menu(4, "&8Clan-Liga &7· " + ranks.size() + " Raenge") {};
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < ranks.size() && i < slots.length; i++) {
            LeagueRank r = ranks.get(i);
            String next = i + 1 < ranks.size() ? "&7bis &f" + (ranks.get(i + 1).rating - 1) : "&7und hoeher";
            ItemStack it = Items.of(r.material, 1, (pack && !r.icon.isEmpty() ? "&f" + plugin.pack().glyphs(":" + r.icon + ":") + " " : "") + r.display(),
                    List.of("&7Wertung ab &f" + r.rating + " " + next, mine == r ? "&a▶ Euer aktueller Rang" : "&8Rang " + (i + 1) + " von " + ranks.size()));
            if (mine == r) Items.glow(it);
            m.set(slots[i], it);
        }
        m.set(31, Items.of(Material.ARROW, "&7Zurueck"), e -> openMain(p));
        m.fillEmpty();
        m.open(p);
    }

    public void openLeaderboard(Player p, boolean previous) {
        boolean pack = plugin.pack() != null && plugin.pack().has(p);
        Menu m = new Menu(6, "&8Rangliste &7· " + (previous ? previousSeasonName : seasonName())) {};
        int[] slots = {13, 12, 14, 28, 29, 30, 31, 32, 33, 34};
        Material[] podium = {Material.GOLD_BLOCK, Material.IRON_BLOCK, Material.COPPER_BLOCK};
        if (previous) {
            for (int i = 0; i < Math.min(10, previousSeason.size()); i++) {
                String[] f = previousSeason.get(i).split(";");
                if (f.length < 6) continue;
                m.set(slots[i], Items.of(i < 3 ? podium[i] : Material.PAPER, 1, "&e" + (i + 1) + ". &8[&b" + f[1] + "&8] &f" + f[0],
                        List.of("&7Rang: " + f[3], "&7Wertung: &f" + f[2], "&a" + f[4] + " S &8· &c" + f[5] + " N")));
            }
            if (previousSeason.isEmpty()) m.set(22, Items.of(Material.BARRIER, "&7Es gibt noch keine vorherige Saison."));
        } else {
            List<Clan> list = ranking();
            int shown = 0;
            for (Clan c : list) {
                if (shown >= 10 || c.leagueMatches < placement()) break;
                int i = shown++;
                ItemStack it = Items.of(i < 3 ? podium[i] : Material.PAPER, 1, "&e" + (i + 1) + ". &8[&b" + c.tag + "&8] &f" + c.name,
                        List.of("&7Rang: " + rankText(c, pack), "&7Wertung: &f" + c.rating, "&a" + c.seasonWins + " S &8· &c" + c.seasonLosses + " N" + (c.seasonDraws > 0 ? " &8· &e" + c.seasonDraws + " U" : ""),
                                "&7Mitglieder: &f" + c.members.size()));
                if (i == 0) Items.glow(it);
                m.set(slots[i], it);
            }
            if (shown == 0) m.set(22, Items.of(Material.BARRIER, 1, "&7Noch kein Clan ist platziert.", List.of("&7Spielt " + placement() + " gewertete Matches: &b/clanduell")));
            Clan mine = plugin.clans().of(p);
            if (mine != null) {
                int pos = mine.leagueMatches >= placement() ? list.indexOf(mine) + 1 : 0;
                m.set(49, Items.of(Material.BLUE_BANNER, 1, "&b&lDEIN CLAN &8[&b" + mine.tag + "&8]", List.of(pos > 0 ? "&7Platz &f" + pos : "&7Nicht platziert (" + mine.leagueMatches + "/" + placement() + " Matches)",
                        "&7Rang: " + rankText(mine, pack), "&a" + mine.seasonWins + " S &8· &c" + mine.seasonLosses + " N")));
            }
        }
        m.set(45, Items.of(previous ? Material.GRAY_DYE : Material.LIME_DYE, 1, "&f" + seasonName(), List.of(previous ? "&7Klicke zum Anzeigen" : "&a▶ wird angezeigt")), e -> openLeaderboard(p, false));
        m.set(46, Items.of(previous ? Material.LIME_DYE : Material.GRAY_DYE, 1, "&f" + (previousSeasonName.isEmpty() ? "Vorherige Saison" : previousSeasonName),
                List.of(previous ? "&a▶ wird angezeigt" : "&7Klicke zum Anzeigen")), e -> openLeaderboard(p, true));
        m.set(53, Items.of(Material.OAK_SIGN, 1, "&7Hinweis", List.of("&7Gewertet werden Clans ab " + placement() + " Matches", "&7in der Saison.")));
        m.set(52, Items.of(Material.ARROW, "&7Zum Clan-Duell"), e -> openMain(p));
        m.fillEmpty();
        m.open(p);
    }

    // ================================================================ Match-Suche
    private void toggleQueue(Player p, Clan c, boolean message) {
        if (queue.remove(c.id) != null) {
            plugin.clans().broadcastClan(c, Msg.get("league.queue-stop", "{prefix}&7Die Match-Suche wurde beendet."));
            return;
        }
        if (!mayManage(p, c)) { Msg.send(p, "league.no-rank", "{prefix}&cNur Moderatoren und hoeher duerfen ein Match suchen."); return; }
        if (readyCount(c) < teamSize()) { Msg.send(p, "league.lineup-missing", "{prefix}&cStellt erst {n} bereite Spieler auf: &f/clanduell aufstellung", "n", String.valueOf(teamSize())); return; }
        if (arenaNames().isEmpty()) { Msg.send(p, "league.no-arena", "{prefix}&cDie Clan-Arena ist noch nicht eingerichtet."); return; }
        queue.put(c.id, System.currentTimeMillis());
        int hour = LocalDateTime.now(RewardService.ZONE).getHour();
        activity[hour]++;
        StringBuilder sb = new StringBuilder();
        for (int v : activity) sb.append(v).append(',');
        plugin.db().kvSet("league-activity", sb.toString());
        plugin.clans().broadcastClan(c, Msg.get("league.queue-start", "{prefix}&d&lMatch-Suche gestartet! &7Gewertet, {n} gegen {n}. Bleibt bereit.", "n", String.valueOf(teamSize())));
    }

    private List<String> arenaNames() {
        ConfigurationSection s = cfg().getConfigurationSection("arenas");
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    private void second() {
        for (Match m : new HashSet<>(matches.values())) tickMatch(m);
        if (queue.size() < 2) return;
        String arena = null;
        for (String a : arenaNames()) if (!busyArenas.contains(a)) { arena = a; break; }
        if (arena == null) return;
        List<Clan> q = new ArrayList<>();
        for (String id : new ArrayList<>(queue.keySet())) {
            Clan c = null;
            for (Clan x : plugin.clans().all()) if (x.id.equals(id)) { c = x; break; }
            if (c == null) { queue.remove(id); continue; }
            if (readyCount(c) < teamSize()) {
                queue.remove(id);
                plugin.clans().broadcastClan(c, Msg.get("league.queue-notready", "{prefix}&cMatch-Suche beendet: Nicht alle aufgestellten Spieler sind bereit."));
                continue;
            }
            q.add(c);
        }
        q.sort(Comparator.comparingInt(c -> c.rating));
        long now = System.currentTimeMillis();
        long cooldown = cfg().getLong("rematch-cooldown-minutes", 10) * 60_000L;
        Clan bestA = null, bestB = null;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < q.size(); i++) for (int j = i + 1; j < q.size(); j++) {
            Clan a = q.get(i), b = q.get(j);
            long waited = (now - Math.min(queue.get(a.id), queue.get(b.id))) / 1000;
            int diff = Math.abs(a.rating - b.rating);
            if (diff > 200 + 25 * waited) continue;
            Long last = lastPairing.get(pairKey(a, b));
            if (last != null && now - last < cooldown && q.size() > 2) continue;
            if (diff < bestDiff) { bestDiff = diff; bestA = a; bestB = b; }
        }
        if (bestA != null) start(bestA, bestB, arena);
    }

    private static String pairKey(Clan a, Clan b) {
        return a.id.compareTo(b.id) < 0 ? a.id + ":" + b.id : b.id + ":" + a.id;
    }

    // ================================================================ Match
    private void start(Clan a, Clan b, String arena) {
        Location l1 = PlayerData.locFromString(cfg().getString("arenas." + arena + ".pos1"));
        Location l2 = PlayerData.locFromString(cfg().getString("arenas." + arena + ".pos2"));
        Kit kit = plugin.kits().get(cfg().getString("kit", "duel_netherite"));
        queue.remove(a.id);
        queue.remove(b.id);
        if (l1 == null || l2 == null || kit == null) {
            for (Clan c : List.of(a, b)) plugin.clans().broadcastClan(c, Msg.get("league.broken", "{prefix}&cDie Clan-Arena oder das Kit ist nicht richtig eingerichtet."));
            return;
        }
        List<Player> ta = new ArrayList<>(), tb = new ArrayList<>();
        for (UUID id : lineup(a)) ta.add(Bukkit.getPlayer(id));
        for (UUID id : lineup(b)) tb.add(Bukkit.getPlayer(id));
        Match m = new Match(a, b, ta, tb, arena);
        busyArenas.add(arena);
        lastPairing.put(pairKey(a, b), System.currentTimeMillis());
        for (Player p : m.all()) {
            boolean teamA = m.teamA(p);
            List<Player> team = teamA ? ta : tb;
            p.closeInventory();
            plugin.duels().saveState(p);
            plugin.duels().setBusy(p, true);
            plugin.duels().prepare(p);
            plugin.kits().equip(p, kit, true);
            matches.put(p.getUniqueId(), m);
            m.alive.add(p.getUniqueId());
            Location base = (teamA ? l1 : l2).clone();
            double off = (team.indexOf(p) - (team.size() - 1) / 2.0) * 2.0;
            // quer zur Blickrichtung verteilen
            double yaw = Math.toRadians(base.getYaw());
            base.add(Math.cos(yaw) * off, 0, Math.sin(yaw) * off);
            plugin.ranks().teleport(p, base);
        }
        for (Clan c : List.of(a, b)) {
            Clan other = c == a ? b : a;
            plugin.clans().broadcastClan(c, Msg.get("league.found", "{prefix}&d&lMatch gefunden! &7Gegner: &f{name} &8[&b{tag}&8]", "name", other.name, "tag", other.tag));
        }
        new org.bukkit.scheduler.BukkitRunnable() {
            int n = 5;
            @Override public void run() {
                if (m.phase != Phase.COUNTDOWN) { cancel(); return; }
                for (Player p : m.all()) {
                    if (!p.isOnline()) continue;
                    Clan enemy = m.teamA(p) ? b : a;
                    p.showTitle(Title.title(Text.c(n > 0 ? "&d" + n : "&a&lKAMPF!"), Text.c("&7gegen &f" + enemy.name + " &8[&b" + enemy.tag + "&8]"),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200))));
                    p.playSound(p.getLocation(), n > 0 ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.ENTITY_ENDER_DRAGON_GROWL, 1, n > 0 ? 1 : 1.5f);
                }
                if (n-- == 0) { m.phase = Phase.FIGHT; cancel(); checkEnd(m); }
            }
        }.runTaskTimer(plugin, 20, 20);
    }

    private void tickMatch(Match m) {
        if (m.phase == Phase.ENDED) return;
        long max = cfg().getLong("max-minutes", 6) * 60_000L;
        long left = max - (System.currentTimeMillis() - m.start);
        int na = m.aliveOf(m.ta), nb = m.aliveOf(m.tb);
        String text = "&b[" + m.a.tag + "] &f" + na + " &8vs &f" + nb + " &c[" + m.b.tag + "] &8┃ &7" + TimeUtil.format(Math.max(0, left));
        for (Player p : m.all()) if (p.isOnline() && matches.get(p.getUniqueId()) == m)
            plugin.scoreboard().bossbar(p, "league", text, Math.max(0, Math.min(1, left / (double) max)), BarColor.PURPLE);
        if (left <= 0 && m.phase == Phase.FIGHT) {
            if (na != nb) finish(m, na > nb ? 1 : -1);
            else {
                double ha = 0, hb = 0;
                for (Player p : m.ta) if (m.alive.contains(p.getUniqueId())) ha += p.getHealth();
                for (Player p : m.tb) if (m.alive.contains(p.getUniqueId())) hb += p.getHealth();
                finish(m, Math.abs(ha - hb) < 2 ? 0 : ha > hb ? 1 : -1);
            }
        }
    }

    private void checkEnd(Match m) {
        if (m.phase == Phase.ENDED) return;
        int na = m.aliveOf(m.ta), nb = m.aliveOf(m.tb);
        if (na == 0 && nb == 0) finish(m, 0);
        else if (na == 0) finish(m, -1);
        else if (nb == 0) finish(m, 1);
    }

    /** result: 1 = Team A gewinnt, -1 = Team B, 0 = unentschieden */
    private void finish(Match m, int result) {
        if (m.phase == Phase.ENDED) return;
        m.phase = Phase.ENDED;
        busyArenas.remove(m.arena);
        Clan a = m.a, b = m.b;
        LeagueRank beforeA = rankOf(a), beforeB = rankOf(b);
        double sa = result > 0 ? 1 : result < 0 ? 0 : 0.5;
        double ea = 1 / (1 + Math.pow(10, (b.rating - a.rating) / 400.0));
        int ka = a.leagueMatches < placement() ? cfg().getInt("k-factor-placement", 60) : cfg().getInt("k-factor", 32);
        int kb = b.leagueMatches < placement() ? cfg().getInt("k-factor-placement", 60) : cfg().getInt("k-factor", 32);
        int da = (int) Math.round(ka * (sa - ea)), db = (int) Math.round(kb * ((1 - sa) - (1 - ea)));
        if (result > 0) { da = Math.max(1, da); db = Math.min(-1, db); }
        if (result < 0) { da = Math.min(-1, da); db = Math.max(1, db); }
        apply(a, b, da, result);
        apply(b, a, db, -result);
        int na = m.aliveOf(m.ta), nb = m.aliveOf(m.tb);
        if (result == 0) Msg.broadcast("league.draw", "{prefix}&d&lClan-Duell: &b[{a}] &7und &b[{b}] &7trennen sich unentschieden.", "a", a.tag, "b", b.tag);
        else {
            Clan w = result > 0 ? a : b, l = result > 0 ? b : a;
            Msg.broadcast("league.result", "{prefix}&d&lClan-Duell: &b[{w}] &7besiegt &c[{l}] &7mit &f{alive} &7Ueberlebenden! &8(&a+{dw}&8 / &c{dl}&8)",
                    "w", w.tag, "l", l.tag, "alive", String.valueOf(result > 0 ? na : nb), "dw", String.valueOf(result > 0 ? da : db), "dl", String.valueOf(result > 0 ? db : da));
        }
        for (Player p : m.all()) {
            boolean teamA = m.teamA(p);
            if (matches.get(p.getUniqueId()) == m) matches.remove(p.getUniqueId());
            if (!p.isOnline()) continue;
            plugin.scoreboard().hideBossbar(p, "league");
            Clan c = teamA ? a : b;
            int d = teamA ? da : db;
            int r = teamA ? result : -result;
            announce(p, c, teamA ? beforeA : beforeB, d, r);
            if (m.alive.contains(p.getUniqueId()) && !p.isDead()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    plugin.duels().setBusy(p, false);
                    if (p.isOnline()) plugin.duels().restoreState(p);
                }, 60);
            }
            if (r > 0) plugin.quests().progress(p, "DUEL_WIN", null, 1);
        }
    }

    private void apply(Clan c, Clan enemy, int delta, int result) {
        c.rating = Math.max(0, c.rating + delta);
        c.leagueMatches++;
        if (result > 0) c.seasonWins++; else if (result < 0) c.seasonLosses++; else c.seasonDraws++;
        c.history.add((result > 0 ? "W" : result < 0 ? "L" : "D") + "|" + (delta >= 0 ? "+" : "") + delta + "|" + enemy.tag + "|" + System.currentTimeMillis());
        while (c.history.size() > 10) c.history.remove(0);
        if (result > 0) plugin.clans().addXp(c, plugin.getConfig().getDouble("clans.xp.league-win", 150));
        plugin.clans().save(c);
    }

    private void announce(Player p, Clan c, LeagueRank before, int delta, int result) {
        boolean pack = plugin.pack() != null && plugin.pack().has(p);
        LeagueRank after = rankOf(c);
        String d = (delta >= 0 ? "&a+" : "&c") + delta;
        String title = result > 0 ? "&a&lSIEG" : result < 0 ? "&c&lNIEDERLAGE" : "&e&lUNENTSCHIEDEN";
        String sub;
        Sound sound = result > 0 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_WITHER_SPAWN;
        if (after == null) sub = "&dPlatzierung &f" + c.leagueMatches + " von " + placement();
        else {
            int bi = before == null ? -1 : ranks.indexOf(before), ai = ranks.indexOf(after);
            if (before == null) { title = "&d&lPLATZIERT"; sound = Sound.UI_TOAST_CHALLENGE_COMPLETE; }
            else if (ai > bi) { title = "&6&lAUFSTIEG"; sound = Sound.UI_TOAST_CHALLENGE_COMPLETE; }
            else if (ai < bi) title = "&c&lABSTIEG";
            sub = rankText(c, pack) + " &f" + c.rating + " " + d;
            if (before == null || ai != bi) Msg.send(p, "league.rank-change", "{prefix}&7Euer Clan ist jetzt {rank} &8(&f{rating}&8)", "rank", rankText(c, pack), "rating", String.valueOf(c.rating));
        }
        p.showTitle(Title.title(Text.c(title), Text.c(sub), Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(3500), Duration.ofMillis(600))));
        p.playSound(p.getLocation(), sound, 0.8f, 1);
        Msg.send(p, "league.summary", "{prefix}&7Clan-Duell beendet: {title} &8┃ &7Wertung {delta} &8→ &f{rating}", "title", title, "delta", d,
                "rating", c.leagueMatches >= placement() ? String.valueOf(c.rating) : "nach der Platzierung");
    }

    private void out(Player p, Match m) {
        if (!m.alive.remove(p.getUniqueId())) return;
        Clan mine = m.teamA(p) ? m.a : m.b;
        for (Player o : m.all()) if (o.isOnline()) Msg.send(o, "league.out", "&8[&dClan-Duell&8] &f{name} &8[&b{tag}&8] &7ist ausgeschieden.", "name", p.getName(), "tag", mine.tag);
        checkEnd(m);
    }

    // ================================================================ Events
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Match m = matches.get(p.getUniqueId());
        if (m == null) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        e.deathMessage(null);
        p.getInventory().clear();
        matches.remove(p.getUniqueId());
        plugin.scoreboard().hideBossbar(p, "league");
        plugin.duels().setBusy(p, false);
        plugin.duels().restoreAfterRespawn(p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && p.isDead()) p.spigot().respawn(); }, 10);
        out(p, m);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Match m = matches.remove(p.getUniqueId());
        if (m != null) out(p, m);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent e) {
        Player attacker = e.getDamager() instanceof Player p ? p : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p2 ? p2 : null;
        if (attacker == null) return;
        Match am = matches.get(attacker.getUniqueId());
        Match vm = e.getEntity() instanceof Player v ? matches.get(v.getUniqueId()) : null;
        if (am == null && vm == null) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        if (am != vm || am.phase != Phase.FIGHT || am.teamA(attacker) == am.teamA(victim)) { e.setCancelled(true); return; }
        e.setCancelled(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Match m = matches.get(e.getPlayer().getUniqueId());
        if (m != null && m.phase == Phase.COUNTDOWN && (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ())) {
            Location to = e.getFrom().clone();
            to.setYaw(e.getTo().getYaw());
            to.setPitch(e.getTo().getPitch());
            e.setTo(to);
        }
    }

    // ================================================================ Saison
    /** Neue Saison: Stand sichern (fuer "Vorherige Saison"), Wertung weich zuruecksetzen. */
    public void resetSeason() {
        StringBuilder sb = new StringBuilder(seasonName()).append('\n');
        int n = 0;
        for (Clan c : ranking()) {
            if (n++ >= 10 || c.leagueMatches < placement()) break;
            LeagueRank r = rankOf(c);
            sb.append(c.name).append(';').append(c.tag).append(';').append(c.rating).append(';').append(r == null ? "-" : r.display()).append(';')
                    .append(c.seasonWins).append(';').append(c.seasonLosses).append('\n');
        }
        plugin.db().kvSet("league-previous", sb.toString());
        previousSeason.clear();
        String[] lines = sb.toString().split("\n");
        previousSeasonName = lines[0];
        for (int i = 1; i < lines.length; i++) previousSeason.add(lines[i]);
        int startRating = cfg().getInt("start-rating", 1000);
        for (Clan c : plugin.clans().all()) {
            c.rating = (c.rating + startRating) / 2;
            c.leagueMatches = 0;
            c.history.clear();
            plugin.clans().save(c);
        }
        queue.clear();
    }

    public void shutdown() {
        for (Match m : new HashSet<>(matches.values())) {
            m.phase = Phase.ENDED;
            for (Player p : m.all()) if (p.isOnline() && !p.isDead() && matches.get(p.getUniqueId()) == m) { plugin.duels().setBusy(p, false); plugin.duels().restoreState(p); }
        }
        matches.clear();
    }
}
