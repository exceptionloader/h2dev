package de.minesmp.core.clan;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import de.minesmp.core.utility.RewardService;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Clan-Rangliste am Spawn ("TOP 100 CLANS"): Saison, Rang-Stufe, Siege / Unentschieden / Niederlagen,
 * Seiten und der eigene Clan. Jeder Spieler in der Naehe bekommt sein eigenes Display (nur fuer ihn sichtbar),
 * damit Seite und "Dein Clan" persoenlich sind. Blaettern: Linksklick = weiter, Schleichen + Linksklick = zurueck.
 */
public final class ClanBoardService implements Listener {
    private record Tier(int points, String name, String color) {}

    private static final int PER_PAGE = 10, MAX = 100;
    private final MineSMP plugin;
    private final NamespacedKey key;
    private final Map<UUID, TextDisplay> boards = new HashMap<>();
    private final Map<UUID, Integer> pages = new HashMap<>();
    private final Map<UUID, String> lastText = new HashMap<>();
    private final List<Tier> tiers = new ArrayList<>();
    private List<Clan> ranking = List.of();

    public ClanBoardService(MineSMP plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "clan_board");
        for (Map<?, ?> m : plugin.getConfig().getMapList("clans.season.tiers")) {
            tiers.add(new Tier(((Number) m.get("points")).intValue(), String.valueOf(m.get("name")), String.valueOf(m.get("color"))));
        }
        if (tiers.isEmpty()) tiers.add(new Tier(0, "Bronze", "&6"));
        tiers.sort((a, b) -> b.points - a.points);
        plugin.listen(this);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getWorlds().forEach(w -> w.getEntitiesByClass(TextDisplay.class).forEach(e -> {
            if (e.getPersistentDataContainer().has(key)) e.remove();
        })));
        Bukkit.getScheduler().runTaskTimer(plugin, () -> ranking = plugin.clans().seasonTop(), 20, 100);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40, 20);
    }

    private Location location() {
        String s = plugin.getConfig().getString("clans.board.location", "");
        Location l = s.isEmpty() ? null : PlayerData.locFromString(s);
        if (l == null && plugin.spawn() != null) l = plugin.spawn().clanBoardLocation();
        return l;
    }

    private Tier tier(Clan c) {
        int pts = c.seasonPoints();
        for (Tier t : tiers) if (pts >= t.points) return t;
        return tiers.get(tiers.size() - 1);
    }

    private String countdown() {
        try {
            LocalDateTime end = LocalDateTime.parse(plugin.getConfig().getString("clans.season.end", "2026-12-31 20:00"),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            long ms = java.time.Duration.between(LocalDateTime.now(RewardService.ZONE), end).toMillis();
            if (ms <= 0) return "&cbeendet";
            long d = ms / 86_400_000L, h = (ms / 3_600_000L) % 24, m = (ms / 60_000L) % 60;
            return "&f" + (d > 0 ? d + "d " + h + "h" : h + "h " + m + "m");
        } catch (Exception e) {
            return "&f?";
        }
    }

    // ---------------------------------------------------------------- Spaltenausrichtung (Minecraft-Standardschrift)
    private static int charWidth(char c) {
        return switch (c) {
            case 'i', '!', '\'', ',', '.', ':', ';', '|' -> 2;
            case 'l', '`' -> 3;
            case 't', 'I', ' ', '[', ']', '(', ')', '{', '}', '*', '"' -> 4;
            case 'f', 'k', '<', '>' -> 5;
            case '@', '~' -> 7;
            default -> c < 0x250 ? 6 : 9; // Latein inkl. Umlaute = 6, Symbole aus Unifont ~9
        };
    }

    /** Breite eines Textes mit &-Farbcodes in Pixeln (fett = +1 je Zeichen). */
    static int width(String s) {
        int w = 0;
        boolean bold = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char k = Character.toLowerCase(s.charAt(++i));
                if (k == '#') { i += 6; continue; }
                if (k == 'l') bold = true; else if ("0123456789abcdefr".indexOf(k) >= 0) bold = false;
                continue;
            }
            switch (c) { // Abstands-Zeichen aus dem Ressourcenpaket
                case '\uE0F0' -> { w -= 1; continue; }
                case '\uE0F1' -> { w += 1; continue; }
                case '\uE0F5' -> { w += 2; continue; }
                case '\uE0F2' -> { w += 3; continue; }
                case '\uE0F3' -> { w += 6; continue; }
                case '\uE0F4' -> { w += 8; continue; }
                default -> { }
            }
            if (c >= 0xE000 && c <= 0xF8FF) continue;
            w += charWidth(c) + (bold ? 1 : 0);
        }
        return w;
    }

    /** Fuellt bis zur Pixel-Position x auf: exakt mit Paket-Abstandszeichen, sonst mit Leerzeichen. */
    private String padTo(String s, int x, boolean pack) {
        int missing = x - width(s);
        if (missing <= 0) return s + " ";
        if (!pack) return s + " ".repeat(Math.max(1, Math.round(missing / 4f)));
        StringBuilder b = new StringBuilder(s);
        String sp8 = plugin.pack().glyphs(":sp8:"), sp3 = plugin.pack().glyphs(":sp3:"), sp1 = plugin.pack().glyphs(":sp1:");
        while (missing >= 8) { b.append(sp8); missing -= 8; }
        while (missing >= 3) { b.append(sp3); missing -= 3; }
        while (missing >= 1) { b.append(sp1); missing -= 1; }
        return b.toString();
    }

    private String row(String nr, String name, String rank, String w, String d, String l, boolean pack) {
        String s = padTo(nr, 20, pack);
        s = padTo(s + name, 112, pack);
        s = padTo(s + rank, 200, pack);
        s = padTo(s + w, 222, pack);
        s = padTo(s + d, 242, pack);
        return s + l;
    }

    private String render(Player p) {
        boolean pack = plugin.pack() != null && plugin.pack().has(p);
        int total = Math.min(MAX, ranking.size());
        int pagesTotal = Math.max(1, (total + PER_PAGE - 1) / PER_PAGE);
        int page = Math.min(pages.getOrDefault(p.getUniqueId(), 0), pagesTotal - 1);
        List<String> out = new ArrayList<>();
        out.add("<g:#55FFFF:#4F7CFF:#FFFFFF>&l⚔  TOP 100 CLANS  ⚔</g>");
        out.add(padTo("&e&l" + plugin.getConfig().getString("clans.season.name", "Saison 1").toUpperCase(), 160, pack) + "&7Endet in " + countdown());
        out.add("&8&m                                                                    ");
        out.add(row("&7#", "&7&lCLAN", "&7&lRANG", "&a▲", "&7=", "&c▼", pack));
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= total) { out.add("&8" + (idx + 1) + "."); continue; }
            Clan c = ranking.get(idx);
            Tier t = tier(c);
            String col = idx == 0 ? "&6" : idx == 1 ? "&f" : idx == 2 ? "&#CD7F32" : "&7";
            out.add(row(col + (idx + 1) + ".", "&f" + c.name, t.color + "✦ " + t.name, "&a" + c.seasonWins, "&7" + c.seasonDraws, "&c" + c.seasonLosses, pack));
        }
        out.add("&8&m                                                                    ");
        out.add("&8« &7Schleichen+Klick   &7Seite &f" + (page + 1) + "&7/&f" + pagesTotal + "   &7Klick &8»");
        out.add("");
        out.add("<g:#FF8AF0:#B45BFF>&lDEIN CLAN</g>");
        Clan mine = plugin.clans().of(p);
        if (mine == null) {
            out.add("&7Du bist in keinem Clan. &b/clan create");
        } else {
            int pos = ranking.indexOf(mine) + 1;
            Tier t = tier(mine);
            out.add(row(pos > 0 && pos <= MAX ? "&b" + pos + "." : "&7-", "&f" + mine.name, t.color + "✦ " + t.name,
                    "&a" + mine.seasonWins, "&7" + mine.seasonDraws, "&c" + mine.seasonLosses, pack));
        }
        out.add("&a▲ &7Siege   &7= Unentschieden   &c▼ &7Niederlagen");
        return String.join("\n", out);
    }

    // ---------------------------------------------------------------- Displays pro Spieler
    private void tick() {
        Location base = location();
        if (base == null || !plugin.getConfig().getBoolean("clans.board.enabled", true)) return;
        double range = plugin.getConfig().getDouble("clans.board.range", 40);
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean near = p.getWorld().equals(base.getWorld()) && p.getLocation().distanceSquared(base) <= range * range;
            TextDisplay d = boards.get(p.getUniqueId());
            if (!near) {
                if (d != null) { d.remove(); boards.remove(p.getUniqueId()); lastText.remove(p.getUniqueId()); }
                continue;
            }
            if (d == null || !d.isValid()) {
                if (!base.isChunkLoaded()) continue;
                float scale = (float) plugin.getConfig().getDouble("clans.board.scale", 1.0);
                d = base.getWorld().spawn(base, TextDisplay.class, td -> {
                    td.setPersistent(false);
                    td.setVisibleByDefault(false);
                    td.setBillboard(Display.Billboard.VERTICAL);
                    td.setAlignment(TextDisplay.TextAlignment.LEFT);
                    td.setLineWidth(400);
                    td.setShadowed(true);
                    td.setBackgroundColor(Color.fromARGB(0xC8, 0x10, 0x16, 0x2A));
                    td.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
                    td.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
                });
                p.showEntity(plugin, d);
                boards.put(p.getUniqueId(), d);
                lastText.remove(p.getUniqueId());
            }
            String text = render(p);
            if (!text.equals(lastText.get(p.getUniqueId()))) {
                d.text(Text.c(text));
                lastText.put(p.getUniqueId(), text);
            }
        }
    }

    /** Blaettern: auf die Tafel schauen und klicken. */
    @EventHandler
    public void onClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_AIR && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!boards.containsKey(p.getUniqueId())) return;
        Location base = location();
        if (base == null || !looksAt(p, base)) return;
        if (!plugin.cooldowns().tryUse(p.getUniqueId(), "clanboard", 250)) return;
        int pagesTotal = Math.max(1, (Math.min(MAX, ranking.size()) + PER_PAGE - 1) / PER_PAGE);
        int page = pages.getOrDefault(p.getUniqueId(), 0) + (p.isSneaking() ? -1 : 1);
        if (page < 0) page = pagesTotal - 1;
        if (page >= pagesTotal) page = 0;
        pages.put(p.getUniqueId(), page);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.1f);
        lastText.remove(p.getUniqueId());
        tick();
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) e.setCancelled(true);
    }

    private boolean looksAt(Player p, Location base) {
        double scale = plugin.getConfig().getDouble("clans.board.scale", 1.0);
        Location center = base.clone().add(0, 2.2 * scale, 0);
        Vector to = center.toVector().subtract(p.getEyeLocation().toVector());
        double dist = to.length();
        if (dist > 20 || dist < 0.5) return false;
        double angle = p.getEyeLocation().getDirection().angle(to);
        return Math.tan(angle) * dist < 3.2 * scale;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        TextDisplay d = boards.remove(e.getPlayer().getUniqueId());
        if (d != null) d.remove();
        pages.remove(e.getPlayer().getUniqueId());
        lastText.remove(e.getPlayer().getUniqueId());
    }

    public void shutdown() {
        boards.values().forEach(TextDisplay::remove);
        boards.clear();
    }
}
