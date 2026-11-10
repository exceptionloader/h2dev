package de.minesmp.core.combat;

import de.minesmp.core.MineSMP;
import de.minesmp.core.combat.KitService.Kit;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PvP-Bereich: Warteschlange fuer Duelle (/queue) mit festen Kits und Sumo, Kit-Editor (/kiteditor, eigene Anordnung
 * der Kit-Items), Kronen (Stufen nach Siegen, /kronen) und eine persoenliche Kronen-Tafel am PvP-Platz.
 */
public final class PvpService implements Listener {
    public record Crown(int wins, String name, String color) {
        public String display() { return color + name; }
    }

    private enum Phase { COUNTDOWN, FIGHT, ENDED }

    private final class Sumo {
        final Player a, b;
        final String arena;
        final double minY;
        final long start = System.currentTimeMillis();
        Phase phase = Phase.COUNTDOWN;
        Sumo(Player a, Player b, String arena, double minY) { this.a = a; this.b = b; this.arena = arena; this.minY = minY; }
        Player other(Player p) { return p.equals(a) ? b : a; }
    }

    private final MineSMP plugin;
    private final NamespacedKey boardKey;
    private final Map<String, List<UUID>> queues = new LinkedHashMap<>();
    private final Map<UUID, Long> queuedSince = new HashMap<>();
    private final Map<UUID, Sumo> sumos = new HashMap<>();
    private final Set<String> busyArenas = new HashSet<>();
    private final List<Crown> crowns = new ArrayList<>();
    private final Map<UUID, TextDisplay> boards = new HashMap<>();
    private final Map<UUID, String> boardText = new HashMap<>();

    public PvpService(MineSMP plugin) {
        this.plugin = plugin;
        this.boardKey = new NamespacedKey(plugin, "crown_board");
        reload();
        plugin.listen(this);
        plugin.command(new SimpleCommand("queue", "minesmp.duel", "Warteschlange fuer Duelle und Sumo", (s, l, a) -> {
            if (!(s instanceof Player p)) return;
            if (a.length > 0 && a[0].equalsIgnoreCase("leave")) { leave(p, true); return; }
            if (a.length > 0) { join(p, a[0].toLowerCase(Locale.ROOT)); return; }
            openQueue(p);
        }, (s, a) -> {
            if (a.length != 1) return List.of();
            List<String> l = new ArrayList<>(List.of("leave", "sumo"));
            plugin.kits().duelKits().forEach(k -> l.add(k.id().toLowerCase(Locale.ROOT)));
            return l;
        }, "q", "warteschlange"));
        plugin.command(new SimpleCommand("sumo", "minesmp.duel", "Sumo-Warteschlange", (s, l, a) -> { if (s instanceof Player p) join(p, "sumo"); }));
        plugin.command(new SimpleCommand("kiteditor", "minesmp.duel", "Kit-Anordnung bearbeiten", (s, l, a) -> { if (s instanceof Player p) openKitSelect(p); }, "kiteditieren", "kitedit"));
        plugin.command(new SimpleCommand("kronen", "minesmp.duel", "Deine Krone", (s, l, a) -> {
            if (!(s instanceof Player p)) return;
            PlayerData d = plugin.data().get(p);
            Crown c = crownOf(d.duelWins), n = nextCrown(d.duelWins);
            Msg.send(p, "crowns.info", "{prefix}&7Deine Krone: {crown} &8┃ &7Siege: &f{wins}{next}", "crown", c == null ? "&8keine" : c.display(), "wins", String.valueOf(d.duelWins),
                    "next", n == null ? "" : " &8┃ &7Noch &f" + (n.wins - d.duelWins) + " &7bis " + n.display());
        }, "crown", "krone"));
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getWorlds().forEach(w -> w.getEntitiesByClass(TextDisplay.class).forEach(e -> {
            if (e.getPersistentDataContainer().has(boardKey)) e.remove();
        })));
        Bukkit.getScheduler().runTaskTimer(plugin, this::second, 20, 20);
    }

    public void reload() {
        crowns.clear();
        for (Map<?, ?> m : plugin.getConfig().getMapList("pvp.crowns")) {
            crowns.add(new Crown(((Number) m.get("wins")).intValue(), String.valueOf(m.get("name")), String.valueOf(m.get("color"))));
        }
        crowns.sort((x, y) -> x.wins - y.wins);
    }

    // ================================================================ Kronen
    public Crown crownOf(int wins) {
        Crown best = null;
        for (Crown c : crowns) if (wins >= c.wins) best = c;
        return best;
    }

    public Crown nextCrown(int wins) {
        for (Crown c : crowns) if (wins < c.wins) return c;
        return null;
    }

    /** Text fuer %crown% */
    public String crownText(Player p) {
        Crown c = crownOf(plugin.data().get(p).duelWins);
        return c == null ? "-" : c.display();
    }

    private void addWin(Player winner, Player loser) {
        PlayerData wd = plugin.data().get(winner), ld = plugin.data().get(loser);
        Crown before = crownOf(wd.duelWins);
        wd.duelWins++;
        ld.duelLosses++;
        wd.dirty = ld.dirty = true;
        checkCrown(winner, before);
        plugin.quests().progress(winner, "DUEL_WIN", null, 1);
    }

    /** Nach einem Sieg pruefen, ob eine neue Krone erreicht wurde (auch von normalen Duellen aufgerufen). */
    public void checkCrown(Player p, Crown before) {
        Crown now = crownOf(plugin.data().get(p).duelWins);
        if (now == null || now == before) return;
        p.showTitle(Title.title(Text.c("&6&l♛ " + Text.strip(now.name).toUpperCase(Locale.ROOT)), Text.c("&7Neue Krone erreicht!"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(500))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1, 1.2f);
        Msg.broadcast("crowns.new", "{prefix}&f{name} &7hat die {crown} &7erreicht!", "name", p.getName(), "crown", now.display());
        plugin.rewards().give(p, plugin.getConfig().getConfigurationSection("pvp.crown-reward"), "Krone");
    }

    // ================================================================ Warteschlange
    public void openQueue(Player p) {
        Menu m = new Menu(4, "&8PvP &7· Warteschlange") {};
        int slot = 10;
        String mine = queueOf(p);
        for (Kit k : plugin.kits().duelKits()) {
            if (slot > 14) break;
            String id = k.id().toLowerCase(Locale.ROOT);
            int n = queues.getOrDefault(id, List.of()).size();
            ItemStack it = Items.of(k.icon(), Math.max(1, n), "&c&lDuell &8· " + k.name(), List.of("&71 gegen 1 mit festem Kit", "&7In der Warteschlange: &f" + n, "&8",
                    id.equals(mine) ? "&cKlicke zum Verlassen" : "&a▶ Klicke zum Beitreten"));
            if (id.equals(mine)) Items.glow(it);
            m.set(slot++, it, e -> { if (id.equals(queueOf(p))) leave(p, true); else join(p, id); openQueue(p); });
        }
        int ns = queues.getOrDefault("sumo", List.of()).size();
        ItemStack sumo = Items.of(Material.SLIME_BALL, Math.max(1, ns), "&e&lSUMO", List.of("&7Ohne Waffen: Stosse deinen Gegner", "&7von der Plattform.", "&7In der Warteschlange: &f" + ns, "&8",
                "sumo".equals(mine) ? "&cKlicke zum Verlassen" : "&a▶ Klicke zum Beitreten"));
        if ("sumo".equals(mine)) Items.glow(sumo);
        m.set(16, sumo, e -> { if ("sumo".equals(queueOf(p))) leave(p, true); else join(p, "sumo"); openQueue(p); });
        PlayerData d = plugin.data().get(p);
        Crown c = crownOf(d.duelWins), n = nextCrown(d.duelWins);
        m.set(30, Items.of(Material.GOLDEN_HELMET, 1, "&6&lKronen", List.of("&7Deine Krone: " + (c == null ? "&8keine" : c.display()), "&7Siege: &a" + d.duelWins + " &8· &7Niederlagen: &c" + d.duelLosses,
                n == null ? "&6Hoechste Krone erreicht!" : "&7Noch &f" + (n.wins - d.duelWins) + " &7Siege bis " + n.display())));
        m.set(32, Items.of(Material.CRAFTING_TABLE, 1, "&b&lKit-Editor", List.of("&7Ordne die Items deiner Duell-Kits", "&7so an, wie du sie brauchst ›")), e -> openKitSelect(p));
        m.fillEmpty();
        m.open(p);
    }

    private String queueOf(Player p) {
        for (Map.Entry<String, List<UUID>> en : queues.entrySet()) if (en.getValue().contains(p.getUniqueId())) return en.getKey();
        return null;
    }

    private void join(Player p, String mode) {
        if (!mode.equals("sumo") && (plugin.kits().get(mode) == null || !plugin.kits().get(mode).duelOnly())) { Msg.send(p, "duel.unknown-kit", "{prefix}&cUnbekanntes Duell-Kit."); return; }
        if (plugin.duels().inDuel(p) || plugin.combat().isTagged(p)) { Msg.send(p, "queue.busy", "{prefix}&cDas geht gerade nicht (im Kampf)."); return; }
        if (mode.equals("sumo") && sumoArenas().isEmpty()) { Msg.send(p, "queue.no-sumo", "{prefix}&cDie Sumo-Plattform ist noch nicht eingerichtet."); return; }
        leave(p, false);
        queues.computeIfAbsent(mode, k -> new ArrayList<>()).add(p.getUniqueId());
        queuedSince.put(p.getUniqueId(), System.currentTimeMillis());
        Msg.send(p, "queue.joined", "{prefix}&aDu bist in der Warteschlange: &f{mode}&a. &7Verlassen: &f/queue leave", "mode", modeName(mode));
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
    }

    private void leave(Player p, boolean message) {
        boolean was = false;
        for (List<UUID> l : queues.values()) was |= l.remove(p.getUniqueId());
        queuedSince.remove(p.getUniqueId());
        if (was && message) Msg.send(p, "queue.left", "{prefix}&7Du hast die Warteschlange verlassen.");
    }

    private String modeName(String mode) {
        if (mode.equals("sumo")) return "Sumo";
        Kit k = plugin.kits().get(mode);
        return k == null ? mode : Text.strip(k.name());
    }

    private void second() {
        for (Map.Entry<String, List<UUID>> en : queues.entrySet()) {
            List<UUID> q = en.getValue();
            q.removeIf(id -> { Player p = Bukkit.getPlayer(id); return p == null || plugin.duels().inDuel(p); });
            for (UUID id : q) {
                Player p = Bukkit.getPlayer(id);
                long sec = (System.currentTimeMillis() - queuedSince.getOrDefault(id, System.currentTimeMillis())) / 1000;
                if (p != null) Msg.actionbar(p, "queue.waiting", "&eWarteschlange &8· &f{mode} &8· &7{time}", "mode", modeName(en.getKey()), "time", String.format("%d:%02d", sec / 60, sec % 60));
            }
            while (q.size() >= 2) {
                Player a = Bukkit.getPlayer(q.get(0)), b = Bukkit.getPlayer(q.get(1));
                String err = en.getKey().equals("sumo") ? startSumo(a, b) : plugin.duels().start(a, b, plugin.kits().get(en.getKey()), null);
                if (err != null) break; // z.B. alle Arenen belegt - spaeter erneut versuchen
                q.remove(a.getUniqueId());
                q.remove(b.getUniqueId());
                queuedSince.remove(a.getUniqueId());
                queuedSince.remove(b.getUniqueId());
            }
        }
        for (Sumo s : new HashSet<>(sumos.values())) {
            if (s.phase == Phase.FIGHT && System.currentTimeMillis() - s.start > plugin.getConfig().getLong("pvp.sumo.max-seconds", 180) * 1000L) endSumo(s, null);
        }
        boardTick();
    }

    // ================================================================ Sumo
    private List<String> sumoArenas() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("pvp.sumo.arenas");
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    private String startSumo(Player a, Player b) {
        String arena = null;
        for (String n : sumoArenas()) if (!busyArenas.contains(n)) { arena = n; break; }
        if (arena == null) return "Alle Sumo-Plattformen sind belegt.";
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("pvp.sumo.arenas." + arena);
        Location l1 = PlayerData.locFromString(s.getString("pos1")), l2 = PlayerData.locFromString(s.getString("pos2"));
        if (l1 == null || l2 == null) return "Sumo-Plattform nicht eingerichtet.";
        Sumo m = new Sumo(a, b, arena, s.getDouble("min-y", l1.getY() - 3));
        busyArenas.add(arena);
        for (Player p : List.of(a, b)) {
            p.closeInventory();
            plugin.duels().saveState(p);
            plugin.duels().setBusy(p, true);
            plugin.duels().prepare(p);
            sumos.put(p.getUniqueId(), m);
        }
        plugin.ranks().teleport(a, l1);
        plugin.ranks().teleport(b, l2);
        new org.bukkit.scheduler.BukkitRunnable() {
            int n = 3;
            @Override public void run() {
                if (m.phase != Phase.COUNTDOWN) { cancel(); return; }
                for (Player p : List.of(a, b)) {
                    if (!p.isOnline()) continue;
                    p.showTitle(Title.title(Text.c(n > 0 ? "&e" + n : "&a&lSUMO!"), Text.c("&7gegen &f" + m.other(p).getName()),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200))));
                    p.playSound(p.getLocation(), n > 0 ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.BLOCK_NOTE_BLOCK_BELL, 1, n > 0 ? 1 : 1.6f);
                }
                if (n-- == 0) { m.phase = Phase.FIGHT; cancel(); }
            }
        }.runTaskTimer(plugin, 20, 20);
        return null;
    }

    private void endSumo(Sumo m, Player winner) {
        if (m.phase == Phase.ENDED) return;
        m.phase = Phase.ENDED;
        busyArenas.remove(m.arena);
        sumos.remove(m.a.getUniqueId());
        sumos.remove(m.b.getUniqueId());
        if (winner != null) {
            Player loser = m.other(winner);
            addWin(winner, loser);
            Msg.broadcast("sumo.result", "{prefix}&e&lSumo: &f{winner} &7stoesst &f{loser} &7von der Plattform!", "winner", winner.getName(), "loser", loser.getName());
        } else {
            for (Player p : List.of(m.a, m.b)) if (p.isOnline()) Msg.send(p, "sumo.draw", "{prefix}&7Sumo endet unentschieden (Zeitlimit).");
        }
        for (Player p : List.of(m.a, m.b)) {
            if (!p.isOnline()) { plugin.duels().setBusy(p, false); continue; }
            p.setFallDistance(0);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                plugin.duels().setBusy(p, false);
                if (p.isOnline()) { p.setFallDistance(0); plugin.duels().restoreState(p); }
            }, winner == null || p.equals(winner) ? 40 : 2);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Sumo m = sumos.get(e.getPlayer().getUniqueId());
        if (m == null) return;
        if (m.phase == Phase.COUNTDOWN) {
            if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ()) {
                Location to = e.getFrom().clone();
                to.setYaw(e.getTo().getYaw());
                to.setPitch(e.getTo().getPitch());
                e.setTo(to);
            }
            return;
        }
        if (m.phase == Phase.FIGHT && (e.getTo().getY() < m.minY || e.getTo().getBlock().isLiquid())) endSumo(m, m.other(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            if (e instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player a && sumos.containsKey(a.getUniqueId())) e.setCancelled(true);
            return;
        }
        Sumo m = sumos.get(victim.getUniqueId());
        if (e instanceof EntityDamageByEntityEvent by) {
            Player attacker = by.getDamager() instanceof Player a ? a : null;
            Sumo am = attacker == null ? null : sumos.get(attacker.getUniqueId());
            if (m == null && am == null) return;
            if (m == null || am != m || m.phase != Phase.FIGHT) { e.setCancelled(true); return; }
            e.setCancelled(false);
            e.setDamage(0);
        } else if (m != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        leave(p, false);
        Sumo m = sumos.get(p.getUniqueId());
        if (m != null) endSumo(m, m.other(p));
        TextDisplay d = boards.remove(p.getUniqueId());
        if (d != null) d.remove();
        boardText.remove(p.getUniqueId());
    }

    public boolean inSumo(Player p) { return sumos.containsKey(p.getUniqueId()); }

    // ================================================================ Kit-Editor
    private void openKitSelect(Player p) {
        Menu m = new Menu(3, "&8Kit-Editor &7· Kit waehlen") {};
        int slot = 10;
        for (Kit k : plugin.kits().duelKits()) {
            if (slot > 16) break;
            boolean custom = plugin.data().get(p).kitLayouts.containsKey(k.id().toLowerCase(Locale.ROOT));
            m.set(slot++, Items.of(k.icon(), 1, k.name(), List.of(custom ? "&aEigene Anordnung gespeichert" : "&7Standard-Anordnung", "&e▶ Klicke zum Bearbeiten")), e -> new KitEditor(p, k).open(p));
        }
        m.fillEmpty();
        m.open(p);
    }

    /** Oben: Inventar (3 Reihen) und darunter die Hotbar. Zwei Klicks tauschen zwei Plaetze. */
    private final class KitEditor extends Menu {
        private final Player player;
        private final Kit kit;
        private final int[] slotItem = new int[36]; // Menue-Slot -> Index im Kit (-1 = leer)
        private int selected = -1;

        KitEditor(Player p, Kit kit) {
            super(5, "&8Kit-Editor &7· " + Text.strip(kit.name()));
            this.player = p;
            this.kit = kit;
            load(plugin.kits().layout(p, kit));
            render();
        }

        private void load(int[] layout) {
            java.util.Arrays.fill(slotItem, -1);
            for (int i = 0; i < kit.items().size(); i++) {
                int inv = layout != null && i < layout.length ? layout[i] : i;   // Inventar-Slot: 0-8 Hotbar, 9-35 Rest
                int menu = inv < 9 ? 27 + inv : inv - 9;                         // Menue: Reihe 4 = Hotbar
                if (menu >= 0 && menu < 36 && slotItem[menu] == -1) slotItem[menu] = i;
            }
        }

        private void render() {
            clear();
            for (int s = 0; s < 36; s++) {
                ItemStack it = slotItem[s] < 0 ? null : kit.items().get(slotItem[s]).clone();
                if (s == selected) it = Items.glow(it == null ? Items.of(Material.LIME_STAINED_GLASS_PANE, "&aAusgewaehlt") : it);
                else if (it == null && s >= 27) it = Items.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7Hotbar " + (s - 26));
                inv.setItem(s, it);
            }
            for (int s = 36; s < 45; s++) inv.setItem(s, FILLER);
            inv.setItem(38, Items.glow(Items.of(Material.LIME_DYE, 1, "&a&lSpeichern", List.of("&7Diese Anordnung fuer alle", "&7Duelle mit diesem Kit nutzen."))));
            inv.setItem(40, Items.of(Material.BOOK, 1, "&b&lSo geht's", List.of("&71. Item anklicken", "&72. Zielplatz anklicken", "&7Die unterste Reihe ist deine Hotbar.")));
            inv.setItem(42, Items.of(Material.BARRIER, 1, "&c&lZuruecksetzen", List.of("&7Standard-Anordnung wiederherstellen")));
        }

        @Override
        public void handleClick(InventoryClickEvent e) {
            e.setCancelled(true);
            if (e.getClickedInventory() != inv) return;
            int s = e.getSlot();
            if (s == 38) {
                int[] layout = new int[kit.items().size()];
                java.util.Arrays.fill(layout, -1);
                for (int m = 0; m < 36; m++) if (slotItem[m] >= 0) layout[slotItem[m]] = m >= 27 ? m - 27 : m + 9;
                StringBuilder sb = new StringBuilder();
                for (int v : layout) sb.append(sb.length() > 0 ? "," : "").append(v);
                PlayerData d = plugin.data().get(player);
                d.kitLayouts.put(kit.id().toLowerCase(Locale.ROOT), sb.toString());
                d.dirty = true;
                Msg.send(player, "kiteditor.saved", "{prefix}&aAnordnung fuer {kit} &agespeichert.", "kit", kit.name());
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1.4f);
                Menu.close(player);
                return;
            }
            if (s == 42) {
                PlayerData d = plugin.data().get(player);
                d.kitLayouts.remove(kit.id().toLowerCase(Locale.ROOT));
                d.dirty = true;
                selected = -1;
                load(null);
                render();
                return;
            }
            if (s < 0 || s >= 36) return;
            if (selected < 0) { if (slotItem[s] >= 0) selected = s; }
            else {
                int t = slotItem[s];
                slotItem[s] = slotItem[selected];
                slotItem[selected] = t;
                selected = -1;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.5f);
            }
            render();
        }
    }

    // ================================================================ Kronen-Tafel (pro Spieler)
    private void boardTick() {
        Location base = plugin.spawn() == null ? null : plugin.spawn().point("crown-board");
        if (base == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean near = p.getWorld().equals(base.getWorld()) && p.getLocation().distanceSquared(base) < 40 * 40;
            TextDisplay d = boards.get(p.getUniqueId());
            if (!near) {
                if (d != null) { d.remove(); boards.remove(p.getUniqueId()); boardText.remove(p.getUniqueId()); }
                continue;
            }
            if (d == null || !d.isValid()) {
                d = base.getWorld().spawn(base, TextDisplay.class, td -> {
                    td.setPersistent(false);
                    td.setVisibleByDefault(false);
                    td.setBillboard(Display.Billboard.VERTICAL);
                    td.setShadowed(true);
                    td.setDefaultBackground(false);
                    td.setBackgroundColor(Color.fromARGB(0x70, 16, 22, 42));
                    td.setLineWidth(240);
                    td.getPersistentDataContainer().set(boardKey, PersistentDataType.BYTE, (byte) 1);
                });
                p.showEntity(plugin, d);
                boards.put(p.getUniqueId(), d);
                boardText.remove(p.getUniqueId());
            }
            PlayerData pd = plugin.data().get(p);
            Crown c = crownOf(pd.duelWins), n = nextCrown(pd.duelWins);
            String text = "<g:#FFE66B:#FF9E2C>&l♛ KRONEN ♛</g>\n&7Deine Krone: " + (c == null ? "&8keine" : c.display()) + "\n&a" + pd.duelWins + " Siege &8· &c" + pd.duelLosses + " Niederlagen\n"
                    + (n == null ? "&6Du hast die hoechste Krone!" : "&fDu brauchst noch &e" + (n.wins - pd.duelWins) + (n.wins - pd.duelWins == 1 ? " Sieg" : " Siege") + "\n&ffuer die " + n.display() + "&f!")
                    + "\n&8Duell & Sumo: &b/queue";
            if (!text.equals(boardText.put(p.getUniqueId(), text))) d.text(Text.c(text));
        }
    }

    public void shutdown() {
        for (Sumo m : new HashSet<>(sumos.values())) {
            m.phase = Phase.ENDED;
            for (Player p : List.of(m.a, m.b)) if (p.isOnline()) { plugin.duels().setBusy(p, false); plugin.duels().restoreState(p); }
        }
        sumos.clear();
        boards.values().forEach(TextDisplay::remove);
        boards.clear();
    }
}
