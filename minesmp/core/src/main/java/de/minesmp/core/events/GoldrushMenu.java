package de.minesmp.core.events;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.events.GoldrushService.Find;
import de.minesmp.core.gui.GuiText;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Menue /goldrausch mit sieben Seiten, aufgebaut wie das Goldrausch-Menue von HugoSMP (eigene Grafiken, MineSMP-Farben):
 * Goldticket (Hauptgewinn), Diese Erze zaehlen, Was du finden kannst, Dein Goldrausch, Goldstaub-Shop, Meilensteine, Rangliste.
 * Mit Ressourcenpaket ist es ein eigenes Fenster in den Serverfarben (Bilder und Masse: tools/build_goldrush.py),
 * die wechselnden Zahlen stehen dabei direkt im Fenster. Ohne Paket (auch Bedrock) erscheint dieselbe Aufteilung
 * als normale Kiste - alle Angaben stehen zusaetzlich in den Hinweistexten der Items.
 */
public final class GoldrushMenu extends Menu {
    public enum Tab {
        TICKET("ticket", "Goldticket - Hauptgewinn", "gr_ticket", Material.PAPER),
        ERZE("erze", "Diese Erze zaehlen", "gr_ic_erze", Material.DIAMOND_ORE),
        FUNDE("funde", "Was du finden kannst", "gr_nugget", Material.GOLD_NUGGET),
        START("start", "Dein Goldrausch", "gr_picke", Material.GOLDEN_PICKAXE),
        SHOP("shop", "Goldstaub-Shop", "gr_ic_shop", Material.EMERALD),
        ZIELE("ziele", "Meilensteine", "gr_ic_ziel", Material.EXPERIENCE_BOTTLE),
        TOP("top", "Rangliste", "gr_ic_top", Material.NETHER_STAR);

        final String page, title, model;
        final Material fallback;

        Tab(String page, String title, String model, Material fallback) {
            this.page = page; this.title = title; this.model = model; this.fallback = fallback;
        }
    }

    private static final TextColor GOLD = TextColor.color(0xFFC83D), WHITE = TextColor.color(0xFFFFFF), CYAN = TextColor.color(0x7CF7FF),
            RED = TextColor.color(0xFF7B6B), GRAY = TextColor.color(0x8FA3B3);
    private static final int[] SHOP_SLOTS = {10, 12, 14, 16, 29, 31, 33};
    private static final int[] TOP_SLOTS = {13, 19, 25, 37, 38, 39, 40, 41, 42, 43};
    private static final Find[] FIND_ROWS = {Find.NUGGET, Find.BAR, Find.STARTOOL, Find.SPAWNER, Find.TICKET};
    private static GuiText gui;

    private final GoldrushService gs;
    private final MineSMP plugin;
    private final Player p;
    private final Tab tab;
    private final boolean pack;

    public GoldrushMenu(GoldrushService gs, Player p, Tab tab) {
        super(6, title(gs, p, tab));
        this.gs = gs;
        this.plugin = gs.plugin();
        this.p = p;
        this.tab = tab;
        this.pack = usesPack(gs, p);
        switch (tab) {
            case START -> start();
            case FUNDE -> finds();
            case ERZE -> ores();
            case TICKET -> ticket();
            case SHOP -> shop();
            case ZIELE -> goals();
            case TOP -> ranking();
        }
        nav();
        if (!pack) fillEmpty();
    }

    private static boolean usesPack(GoldrushService gs, Player p) {
        MineSMP pl = gs.plugin();
        if (gui == null) gui = new GuiText(pl, "goldrush-gui.yml");
        return gui.ready() && pl.pack() != null && pl.pack().has(p);
    }

    private static int cx(int col) { return 16 + 18 * col; }

    /** Kisten, aus denen das Goldticket jeden Tag einen Gratis-Schluessel gibt (goldrausch.yml -> ticket.daily-keys). */
    static List<String> ticketCrates(GoldrushService gs) {
        ConfigurationSection k = gs.cfg().getConfigurationSection("ticket.daily-keys");
        List<String> out = new ArrayList<>();
        if (k != null) for (String id : k.getKeys(false)) if (k.getInt(id) > 0) out.add(id);
        return out;
    }

    /** Kurzer Kistenname fuer die Karte (passt in 30 Pixel). */
    static String crateLabel(GoldrushService gs, String id) {
        String n = gs.plugin().crates() == null ? id : Text.strip(gs.plugin().crates().displayName(id));
        n = n.replace("-Crate", "").replace(" Crate", "").replace("-Kiste", "").replace(" Kiste", "").trim();
        while (n.length() > 1 && GuiText.width(n, false) > 31) n = n.substring(0, n.length() - 1);
        return n;
    }

    private static String rankText(int rank) { return rank == 0 ? "-" : rank > 100 ? "100+" : "#" + rank; }

    /** Chance als "1:40" (gerundet). */
    static String odds(double chance) {
        return chance <= 0 ? "-" : "1:" + Math.max(1, Math.round(1 / chance));
    }

    /** Fortschritt zum naechsten Server-Ziel (0..1). */
    private static double serverRatio(GoldrushService gs) {
        Long next = gs.communityTiers().higherKey(gs.totalOres());
        return next == null ? 1 : Math.min(1, (double) gs.totalOres() / next);
    }

    private static Component title(GoldrushService gs, Player p, Tab tab) {
        if (!usesPack(gs, p)) return Text.c("&8Goldrausch &8» &6" + tab.title);
        GuiText.Builder b = gui.page(tab.page);
        int px = gui.pillX(), T = GuiText.TITLE;
        boolean on = gs.active();
        switch (tab) {
            case START -> {
                b.text(gs.leftText(), px, T, WHITE, false);
                String[] top = {Text.compact(gs.won(p)), Text.compact(gs.ores(p)), Text.compact(gs.finds(p))};
                String[] bottom = {on ? GoldrushService.factor(gs.luck(p)) : "-", rankText(gs.rank(p)), Text.compact(gs.wonToday(p))};
                for (int i = 0; i < 3; i++) {
                    b.text(top[i], 27 + 54 * i, 2, i == 1 ? CYAN : GOLD, false);
                    b.text(bottom[i], 27 + 54 * i, 4, i == 1 ? GOLD : CYAN, false);
                }
            }
            case FUNDE -> {
                for (int r = 0; r < FIND_ROWS.length; r++) b.right(odds(gs.chance(FIND_ROWS[r])), 163, r, r == FIND_ROWS.length - 1 ? GOLD : WHITE, false);
            }
            case ERZE -> b.text(on ? GoldrushService.factor(gs.luck(p)) : "-", px, T, GOLD, false);
            case TICKET -> {
                b.text(gs.leftText(), px, T, WHITE, false);
                List<String> crates = ticketCrates(gs);
                for (int i = 0; i < Math.min(4, crates.size()); i++) b.centered(crateLabel(gs, crates.get(i)), cx(1 + 2 * i), 3, GOLD, false);
            }
            case SHOP -> {
                long have = gs.dust(p);
                b.text(Text.compact(have), px, T, GOLD, false);
                List<GoldrushService.ShopEntry> list = gs.shopEntries();
                for (int i = 0; i < Math.min(SHOP_SLOTS.length, list.size()); i++) {
                    int slot = SHOP_SLOTS[i];
                    b.centered(Text.compact(list.get(i).price()), cx(slot % 9), slot / 9 + 1, have >= list.get(i).price() ? GOLD : RED, false);
                }
            }
            case ZIELE -> {
                long ores = gs.ores(p);
                b.text(Text.compact(ores), px, T, CYAN, false);
                int next = -1;
                for (GoldrushService.Milestone m : gs.milestones()) if (ores < m.ores()) { next = m.ores(); break; }
                b.bar(next < 0 ? 1 : (double) ores / next);
                b.centered(next < 0 ? "Alle Meilensteine erreicht" : Text.money(ores) + " / " + Text.money(next) + " Erze", 88, 4, WHITE, false);
            }
            case TOP -> {
                b.text(rankText(gs.rank(p)), px, T, GOLD, false);
                List<GoldrushService.Top> list = gs.top(p);
                int[] centers = {88, 34, 142};
                for (int i = 0; i < Math.min(3, list.size()); i++) b.centered(Text.compact(list.get(i).money()), centers[i], 3, WHITE, false);
            }
        }
        return b.build();
    }

    // ---------------------------------------------------------------- Bausteine

    private ItemStack icon(String model, Material fallback, String name, List<String> lore) {
        ItemStack it = Items.of(pack ? Material.PAPER : fallback, 1, name, lore);
        if (pack) it.editMeta(m -> m.setItemModel(new NamespacedKey("minesmp", model)));
        return it;
    }

    /** Vorhandenes Item mit neuem Namen und Hinweistext (z.B. das echte Custom Item als Symbol). */
    private static ItemStack labeled(ItemStack it, String name, List<String> lore) {
        it.editMeta(m -> {
            m.displayName(Text.item(name));
            m.lore(Text.itemLore(lore));
            m.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        });
        return it;
    }

    private void go(Tab t) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        new GoldrushMenu(gs, p, t).open(p);
    }

    private void nav() {
        int slot = 45;
        for (Tab t : Tab.values()) {
            ItemStack it = icon(t.model, t.fallback, (t == tab ? "&b&l" : "&f") + t.title, List.of(t == tab ? "&8Diese Seite" : "&eKlick: &7oeffnen"));
            if (t == tab && !pack) Items.glow(it);
            set(slot++, it, e -> { if (t != tab) go(t); });
        }
        set(53, icon("gr_ic_x", Material.BARRIER, "&cSchliessen", List.of()), e -> Menu.close(p));
    }

    private String state() {
        return gs.active() ? "&alaeuft noch &f" + TimeUtil.format(gs.left() - gs.left() % 60_000) : gs.everRan() ? "&7beendet" : "&7noch nicht gestartet";
    }

    private static String pct(double v) { return String.valueOf(Math.round(v * 100)); }

    private List<String> howItWorks() {
        var cfg = gs.cfg();
        return List.of(
                "&7In &fjedem natuerlichen Erz &7kann ein",
                "&7Fund stecken - egal wann du spielst:",
                "&6Goldnugget&7, &6Goldbarren&7, &eStar-Tool&7,",
                "&dSpawner &7und das &6&lGoldticket&7.",
                "",
                "&aGlueck &8- &7zuegig weiter abbauen,",
                "&7Goldene Stunde (&f" + cfg.getString("golden-hour.start", "19:00") + " Uhr&7), Server-Ziel",
                "&7und Items erhoehen deine Chancen.",
                "&6Goldstaub &8- &7gibt es fuer jedes Erz,",
                "&7im Shop tauschst du ihn gegen Items.",
                "",
                "&8Von Spielern gesetzte Erze zaehlen nicht.");
    }

    // ---------------------------------------------------------------- Seiten

    private void start() {
        PlayerData d = gs.data(p);
        set(4, icon("gr_leer", Material.BOOK, "&6&lGoldrausch &8- " + state(), howItWorks()));
        set(18, icon("gr_ic_geld", Material.GOLD_INGOT, "&6Gewonnen: &a" + plugin.eco().format(gs.won(p)), List.of(
                "&7Goldnuggets: &f" + gs.found(p, Find.NUGGET),
                "&7Goldbarren: &f" + gs.found(p, Find.BAR),
                "",
                "&7Geld-Funde landen direkt auf deinem Konto.")));
        long ores = gs.ores(p);
        List<String> oreLore = new ArrayList<>();
        GoldrushService.Milestone next = null;
        for (GoldrushService.Milestone m : gs.milestones()) if (ores < m.ores()) { next = m; break; }
        oreLore.add("&7Goldstaub: &6" + Text.money(gs.dust(p)));
        oreLore.add(next == null ? "&aAlle Meilensteine erreicht!" : "&7Naechster Meilenstein: &f" + next.name());
        if (next != null) oreLore.add("&7Noch &f" + Text.money(next.ores() - ores) + " &7Erze");
        oreLore.add("");
        oreLore.add("&eKlick: &7Meilensteine");
        set(21, icon("gr_ic_erze", Material.IRON_PICKAXE, "&bErze abgebaut: &f" + Text.money(ores), oreLore), e -> go(Tab.ZIELE));
        set(24, icon("gr_nugget", Material.GOLD_NUGGET, "&6Funde: &f" + Text.money(gs.finds(p)), List.of(
                "&7Goldnugget: &f" + gs.found(p, Find.NUGGET),
                "&7Goldbarren: &f" + gs.found(p, Find.BAR),
                "&7Star-Tool: &f" + gs.found(p, Find.STARTOOL),
                "&7Spawner: &f" + gs.found(p, Find.SPAWNER),
                "&7Goldticket: &f" + gs.found(p, Find.TICKET),
                "",
                "&eKlick: &7was du finden kannst")), e -> go(Tab.FUNDE));
        set(36, icon("gr_ic_glueck", Material.EMERALD, "&aDein Glueck: &f" + (gs.active() ? GoldrushService.factor(gs.luck(p)) : "-"), luckLore()), e -> go(Tab.ERZE));
        int rank = gs.rank(p);
        List<String> rankLore = new ArrayList<>();
        List<GoldrushService.Top> top = gs.top(p);
        String[] medal = {"&e1.", "&f2.", "&63."};
        for (int i = 0; i < Math.min(3, top.size()); i++) rankLore.add(medal[i] + " &f" + top.get(i).name() + " &8- &a" + plugin.eco().format(top.get(i).money()));
        if (top.isEmpty()) rankLore.add("&7Noch niemand in der Rangliste.");
        rankLore.add("");
        rankLore.add("&7Die Top 10 gewinnen am Ende.");
        rankLore.add("&eKlick: &7Rangliste");
        set(39, icon("gr_ic_platz", Material.GOLDEN_HELMET, "&6Dein Platz: &f" + rankText(rank), rankLore), e -> go(Tab.TOP));
        set(42, icon("gr_ic_heute", Material.CLOCK, "&bHeute gewonnen: &a" + plugin.eco().format(gs.wonToday(p)), List.of(
                "&7Bestes Glueck bisher: &f" + (d.stat("gr_best") > 0 ? GoldrushService.factor(d.stat("gr_best") / 100.0) : "-"),
                "&7Goldene Stunde: &f" + gs.goldenHourText())));
    }

    private List<String> luckLore() {
        var cfg = gs.cfg();
        List<String> l = new ArrayList<>();
        double fever = gs.fever(p);
        l.add((fever > 0 ? "&7Goldfieber: &a+" + pct(fever) + " %" : "&8Goldfieber: -") + " &8(bis +" + pct(cfg.getDouble("fever.max", 0.5)) + " %)");
        boolean pick = "GOLD_PICKE".equals(plugin.customItems().abilityOf(p.getInventory().getItemInMainHand()));
        boolean helm = "GOLD_HELM".equals(plugin.customItems().abilityOf(p.getInventory().getHelmet()));
        l.add(pick ? "&7Goldgraeber-Spitzhacke: &a+" + pct(cfg.getDouble("items.pick-bonus", 0.2)) + " %" : "&8Goldgraeber-Spitzhacke: -");
        l.add(helm ? "&7Grubenhelm: &a+" + pct(cfg.getDouble("items.helmet-bonus", 0.1)) + " %" : "&8Grubenhelm: -");
        l.add(gs.communityBonus() > 0 ? "&7Server-Ziel: &a+" + pct(gs.communityBonus()) + " %" : "&8Server-Ziel: -");
        l.add(gs.elixirActive(p) ? "&7Elixier: &dx" + GoldrushService.trim(cfg.getDouble("items.elixir-multiplier", 1.5)) + " &8(noch " + TimeUtil.format(gs.elixirLeft(p)) + ")" : "&8Elixier: -");
        l.add(gs.goldenHour() ? "&7Goldene Stunde: &ex" + GoldrushService.trim(cfg.getDouble("golden-hour.multiplier", 2.0)) + " &8(" + gs.goldenHourText() + ")"
                : "&8Goldene Stunde: " + gs.goldenHourText());
        l.add("");
        l.add("&7Glueck erhoeht die Chance auf Goldnugget,");
        l.add("&7Goldbarren, Star-Tool und Spawner.");
        l.add("&8Das Goldticket ist fuer alle gleich.");
        return l;
    }

    private List<String> serverLore() {
        List<String> l = new ArrayList<>();
        l.add("&7Alle zusammen: &f" + Text.money(gs.totalOres()) + " Erze &8(" + Math.round(serverRatio(gs) * 100) + " % bis zum naechsten Ziel)");
        l.add("");
        for (Map.Entry<Long, Double> e : gs.communityTiers().entrySet())
            l.add((gs.totalOres() >= e.getKey() ? "&a✔ " : "&8✘ ") + Text.money(e.getKey()) + " Erze: &f+" + pct(e.getValue()) + " % Glueck &7fuer alle");
        l.add("");
        l.add(gs.communityBonus() > 0 ? "&7Aktuell: &a+" + pct(gs.communityBonus()) + " % &7fuer alle" : "&7Noch kein Bonus erreicht.");
        return l;
    }

    /** Liste "Was du finden kannst": eine Reihe je Fund, die Chance steht rechts im Fenster. */
    private void finds() {
        var cfg = gs.cfg();
        double luck = gs.active() ? gs.luck(p) : 1;
        for (int r = 0; r < FIND_ROWS.length; r++) {
            Find f = FIND_ROWS[r];
            List<String> lore = new ArrayList<>();
            ItemStack base;
            String name;
            switch (f) {
                case NUGGET -> {
                    base = plugin.customItems().create("goldnugget");
                    name = "&6&lGoldnugget";
                    lore.add("&a" + gs.moneyText(f) + " &7direkt aufs Konto.");
                }
                case BAR -> {
                    base = plugin.customItems().create("goldbarren");
                    name = "&6&lGoldbarren";
                    lore.add("&a" + gs.moneyText(f) + " &7direkt aufs Konto.");
                    lore.add("&7Mit Gluecks-Hufeisen doppelte Chance.");
                }
                case STARTOOL -> {
                    base = plugin.customItems().create("sternenpicke");
                    name = "&e&lStar-Tool";
                    lore.add("&7Ein zufaelliges Star-Tool, nur fuer dich:");
                    StringBuilder sb = new StringBuilder();
                    for (String id : cfg.getStringList("finds.startool.items")) {
                        ItemStack it = plugin.customItems().create(id);
                        if (it != null) sb.append(sb.length() == 0 ? "" : ", ").append(Text.strip(Items.name(it)));
                    }
                    for (String line : wrap(sb.toString(), 36)) lore.add("&f" + line);
                }
                case SPAWNER -> {
                    base = new ItemStack(Material.SPAWNER);
                    name = "&d&lSpawner";
                    lore.add("&7Ein zufaelliger Spawner:");
                    StringBuilder sb = new StringBuilder();
                    for (String type : cfg.getStringList("finds.spawner.types")) {
                        ItemStack it = plugin.spawners() == null ? null : plugin.spawners().item(type, 1);
                        if (it != null) sb.append(sb.length() == 0 ? "" : ", ").append(Text.strip(Items.name(it)).replace("-Spawner", "").replace(" Spawner", ""));
                    }
                    for (String line : wrap(sb.toString(), 36)) lore.add("&f" + line);
                }
                default -> {
                    base = plugin.customItems().create("goldticket");
                    name = "&6&l✦ Goldticket &8- &eHauptgewinn";
                    lore.add("&f" + cfg.getInt("ticket.days", 14) + " Tage &7jeden Tag Gratis-Schluessel,");
                    lore.add("&b" + cfg.getInt("ticket.premium-days", 30) + " Tage Premium&7, goldener Name, Aura.");
                    lore.add("&7Hoechstens &f" + cfg.getInt("ticket.max-per-day", 1) + " am Tag &7auf dem ganzen Server.");
                }
            }
            if (base == null) base = new ItemStack(f == Find.TICKET ? Material.PAPER : f == Find.BAR ? Material.GOLD_INGOT : Material.GOLD_NUGGET);
            lore.add("");
            double c = gs.chance(f);
            if (f == Find.TICKET) {
                lore.add("&7Chance je Erz: &f" + odds(c) + " &8(fuer alle gleich)");
                lore.add("&7Ab &f" + cfg.getInt("ticket.min-ores", 150) + " &7Erzen im Goldrausch.");
            } else {
                lore.add("&7Chance je Erz: &f" + odds(c));
                if (gs.active() && luck > 1.001) lore.add("&7Mit deinem Glueck (" + GoldrushService.factor(luck) + "): &a" + odds(c * luck));
            }
            lore.add("&7Du hast gefunden: &f" + gs.found(p, f));
            if (f == Find.TICKET) { lore.add(""); lore.add("&eKlick: &7alles zum Goldticket"); }
            set(r * 9, labeled(base, name, lore), f == Find.TICKET ? e -> go(Tab.TICKET) : null);
        }
    }

    private static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : s.split(" ")) {
            if (line.length() > 0 && line.length() + w.length() + 1 > width) { out.add(line.toString()); line.setLength(0); }
            line.append(line.length() == 0 ? "" : " ").append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private void ores() {
        var cfg = gs.cfg();
        set(4, icon("gr_leer", Material.BOOK, "&6Diese Erze zaehlen", List.of(
                "&7Tiefenschiefer-Erze zaehlen genauso.",
                "&7In jedem Erz kann ein Fund stecken,",
                "&7egal wann du spielst.",
                "",
                "&cZaehlt nicht: &7Netherquarzerz, Nethergolderz",
                "&7und von Spielern gesetzte Erze.")));
        int col = 0;
        for (Material m : GoldrushService.ORE_ORDER) {
            if (!gs.isOre(m) || col >= 9) continue;
            List<String> lore = new ArrayList<>();
            lore.add("&a✔ &7Zaehlt im Goldrausch");
            if (Material.matchMaterial("DEEPSLATE_" + m.name()) != null) lore.add("&7Tiefenschiefer-Variante genauso.");
            lore.add("&7Goldstaub je Erz: &6" + gs.dustFor(m));
            set(9 + col++, Items.of(m, 1, "&6" + GoldrushService.oreName(m), lore));
        }
        set(31, icon("gr_ic_fieber", Material.BLAZE_POWDER, "&6Goldfieber", List.of(
                "&7Jedes Erz in Folge: &a+" + pct(cfg.getDouble("fever.per-ore", 0.01)) + " % Glueck&7,",
                "&7hoechstens &a+" + pct(cfg.getDouble("fever.max", 0.5)) + " %&7.",
                "&7Nach &f" + cfg.getInt("fever.window-seconds", 12) + " Sekunden &7ohne Erz",
                "&7faengt es wieder bei null an.")));
        set(32, icon("gr_picke", Material.GOLDEN_PICKAXE, "&6Ausruestung", List.of(
                "&7Goldgraeber-Spitzhacke: &a+" + pct(cfg.getDouble("items.pick-bonus", 0.2)) + " % Glueck",
                "&7Grubenhelm: &a+" + pct(cfg.getDouble("items.helmet-bonus", 0.1)) + " % Glueck",
                "&7Gluecks-Hufeisen (Zweithand):",
                "&7Goldbarren-Chance &ax" + GoldrushService.trim(cfg.getDouble("items.horseshoe-factor", 2.0)),
                "",
                "&eKlick: &7zum Shop")), e -> go(Tab.SHOP));
        set(33, icon("gr_elixier", Material.HONEY_BOTTLE, "&dGoldfieber-Elixier", List.of(
                "&f" + cfg.getInt("items.elixir-minutes", 15) + " Minuten &7Glueck &dx" + GoldrushService.trim(cfg.getDouble("items.elixir-multiplier", 1.5)) + "&7.",
                gs.elixirActive(p) ? "&aAktiv: noch " + TimeUtil.format(gs.elixirLeft(p)) : "&8Gerade nicht aktiv.")));
        set(34, icon("gr_ic_zeit", Material.CLOCK, "&eGoldene Stunde", List.of(
                "&7Jeden Tag um &f" + cfg.getString("golden-hour.start", "19:00") + " Uhr&7, &f" + cfg.getInt("golden-hour.minutes", 60) + " Minuten&7:",
                "&7Glueck &ex" + GoldrushService.trim(cfg.getDouble("golden-hour.multiplier", 2.0)) + " &7fuer alle.",
                "",
                "&7Naechste: &f" + gs.goldenHourText())));
        set(35, icon("gr_ic_server", Material.BEACON, "&bServer-Ziel", serverLore()));
        set(44, icon("gr_ic_info", Material.BOOK, "&cDas Event wird moderiert", List.of(
                "&7Makros, X-Ray, Auto-Miner und andere",
                "&7Cheats sind strengstens verboten und",
                "&7werden mit einem &cBann &7bestraft.",
                "",
                "&7Es zaehlen nur natuerliche Erze.",
                "&7Nicht am Spawn.")));
    }

    private void ticket() {
        var cfg = gs.cfg();
        int days = cfg.getInt("ticket.days", 14);
        List<String> main = new ArrayList<>(List.of(
                "&7Ein Schlag ins richtige Erz und du",
                "&7raeumst &f" + days + " Tage &7lang jeden Tag ab.",
                "&6Jeden Tag ein Gratis-Schluessel aus jeder Kiste.",
                "",
                "&7Bau Erze ab. &6&lJedes Erz kann es sein!",
                "&7Chance je Erz: &f" + odds(gs.chance(Find.TICKET)) + " &8(fuer alle gleich)",
                "&7Hoechstens &f" + cfg.getInt("ticket.max-per-day", 1) + " am Tag&7, insgesamt &f" + cfg.getInt("ticket.max-total", 14) + "&7."));
        if (cfg.getBoolean("ticket.guarantee.enabled", true)) {
            main.add("&7Tages-Garantie: findet bis zum Abend niemand");
            main.add("&7eins, steckt es im naechsten Erz.");
        }
        main.add("&7Voraussetzung: &f" + cfg.getInt("ticket.min-ores", 150) + " Erze &7im Goldrausch.");
        main.add("");
        main.add("&7Goldtickets gefunden: &f" + gs.ticketsTotal() + " &8(heute " + gs.ticketsToday() + ")");
        List<String> finders = gs.finders();
        if (finders.isEmpty()) main.add("&8Noch keins gefunden - vielleicht bist du der Erste!");
        for (int i = 0; i < Math.min(5, finders.size()); i++) {
            String[] nt = finders.get(i).split(":");
            long when = 0;
            try { when = Long.parseLong(nt.length > 1 ? nt[1] : "0"); } catch (NumberFormatException ignored) { }
            long ago = Math.max(60_000, System.currentTimeMillis() - when);
            main.add("&8\u00BB &6" + nt[0] + " &8vor " + TimeUtil.format(ago - ago % 60_000));
        }
        ItemStack hero = icon("gr_leer", Material.PAPER, "&6&l\u2726 GOLDTICKET &8- &eHauptgewinn", main);
        if (pack) for (int i = 0; i < 18; i++) set(i, hero.clone());        // ganzes Banner zeigt den Hinweistext
        else set(4, hero);
        // Karten: jeden Tag ein Gratis-Schluessel aus jeder Kiste
        List<String> crates = ticketCrates(gs);
        ConfigurationSection dk = cfg.getConfigurationSection("ticket.daily-keys");
        for (int i = 0; i < Math.min(4, crates.size()); i++) {
            String id = crates.get(i);
            int n = dk == null ? 1 : dk.getInt(id, 1);
            ItemStack key = plugin.crates() == null ? null : plugin.crates().keyItem(id, 1);
            if (key == null) key = new ItemStack(Material.TRIPWIRE_HOOK);
            String name = plugin.crates() == null ? id : plugin.crates().displayName(id);
            set(19 + 2 * i, labeled(key, "&e" + n + "x " + name + "-Schluessel", List.of(
                    "&7Mit Goldticket &f" + days + " Tage lang",
                    "&7jeden Tag gratis.",
                    "", gs.hasTicket(p) ? (gs.dailyReady(p) ? "&aAbholen: unten rechts" : "&7Heute schon abgeholt.") : "&8Du brauchst ein Goldticket.")));
        }
        int pd = cfg.getInt("ticket.premium-days", 30);
        set(36, icon("gr_ic_top", Material.GOLDEN_HELMET, "&b" + pd + " Tage Premium", List.of(
                "&7Der Rang kommt sofort dazu - selbst",
                "&7oder als &bGutschein &7zum Verschenken,",
                "&7wenn du Premium schon hast.")));
        set(40, Items.of(Material.NAME_TAG, 1, "&6Goldener Name &7+ &6Aura", List.of(
                "&7Dein Name glaenzt golden - im Chat,",
                "&7in der Tab-Liste und ueber dem Kopf.",
                "&7Goldene Funken kreisen um dich,",
                "&7solange dein Goldticket gilt.")));
        // Dein Ticket (unten rechts)
        if (gs.hasTicket(p)) {
            boolean ready = gs.dailyReady(p);
            ItemStack own = plugin.customItems().create("goldticket");
            if (own == null) own = new ItemStack(Material.PAPER);
            List<String> lore = new ArrayList<>();
            lore.add("&7Gilt noch &f" + GoldrushService.shortSpan(gs.ticketUntil(p) - System.currentTimeMillis()));
            lore.add("");
            lore.add(ready ? "&aKlick: heutige Gratis-Schluessel abholen" : "&7Heutige Schluessel schon abgeholt.");
            lore.add("&8Morgen gibt es neue.");
            labeled(own, "&6&lDein Goldticket", lore);
            if (!ready) own.editMeta(m -> m.setEnchantmentGlintOverride(false));
            set(44, own, e -> { if (gs.claimDaily(p)) new GoldrushMenu(gs, p, Tab.TICKET).open(p); });
        } else {
            set(44, icon("gr_ic_schloss", Material.GRAY_DYE, "&7Noch kein Goldticket", List.of(
                    "&7Bau Erze ab - jedes kann es sein.",
                    "&7Du hast abgebaut: &f" + Text.money(gs.ores(p)) + " Erze")));
        }
    }

    private void shop() {
        long have = gs.dust(p);
        List<GoldrushService.ShopEntry> list = gs.shopEntries();
        for (int i = 0; i < Math.min(SHOP_SLOTS.length, list.size()); i++) {
            GoldrushService.ShopEntry en = list.get(i);
            ItemStack it = plugin.customItems().create(en.item());
            if (it == null) continue;
            it.setAmount(Math.min(en.amount(), it.getMaxStackSize()));
            boolean ok = have >= en.price();
            it.editMeta(m -> {
                List<Component> lore = m.hasLore() ? new ArrayList<>(m.lore()) : new ArrayList<>();
                lore.add(Component.empty());
                lore.add(Text.item("&7Preis: &6" + Text.money(en.price()) + " Goldstaub" + (en.amount() > 1 ? " &8(" + en.amount() + " Stueck)" : "")));
                lore.add(Text.item(ok ? "&aKlick: kaufen" : "&cDir fehlen " + Text.money(en.price() - have) + " Goldstaub"));
                m.lore(lore);
            });
            set(SHOP_SLOTS[i], it, e -> { if (gs.buy(p, en)) new GoldrushMenu(gs, p, Tab.SHOP).open(p); });
        }
        set(4, icon("gr_staub", Material.GLOWSTONE_DUST, "&6Goldstaub-Shop", List.of(
                "&7Du hast &6" + Text.money(have) + " Goldstaub&7.",
                "&7Goldstaub gibt es fuer jedes Erz,",
                "&7bei Meilensteinen und mit der Goldpfanne.",
                "",
                "&8Er verfaellt, wenn der naechste",
                "&8Goldrausch beginnt.")));
    }

    private void goals() {
        long ores = gs.ores(p);
        List<GoldrushService.Milestone> list = gs.milestones();
        for (int i = 0; i < Math.min(7, list.size()); i++) {
            GoldrushService.Milestone m = list.get(i);
            boolean done = gs.claimed(p, i), ready = !done && ores >= m.ores();
            List<String> lore = new ArrayList<>();
            lore.add("&7Ziel: &f" + Text.money(m.ores()) + " Erze &8(du: " + Text.money(ores) + ")");
            lore.add("");
            lore.add("&7Belohnung:");
            for (String line : gs.rewardLines(m.reward())) lore.add("&8» " + line);
            lore.add("");
            lore.add(done ? "&a✔ Abgeholt" : ready ? "&aKlick: Belohnung abholen" : "&7Noch &f" + Text.money(m.ores() - ores) + " &7Erze");
            ItemStack it = done ? icon("gr_ic_haken", Material.LIME_DYE, "&a" + (i + 1) + ". " + m.name(), lore)
                    : ready ? Items.glow(icon("gr_ic_ziel", Material.CHEST_MINECART, "&6&l" + (i + 1) + ". " + m.name(), lore))
                    : icon("gr_ic_schloss", Material.GRAY_DYE, "&7" + (i + 1) + ". " + m.name(), lore);
            final int index = i;
            set(10 + i, it, e -> { if (gs.claim(p, index)) new GoldrushMenu(gs, p, Tab.ZIELE).open(p); });
        }
        set(4, icon("gr_leer", Material.BOOK, "&6Meilensteine", List.of(
                "&7Jedes Erz im Goldrausch zaehlt.",
                "&7Erreichte Stufen hier anklicken.",
                "",
                "&7Deine Erze: &f" + Text.money(ores))));
    }

    private void ranking() {
        List<GoldrushService.Top> list = gs.top(p);
        String me = p.getUniqueId().toString();
        for (int i = 0; i < Math.min(TOP_SLOTS.length, list.size()); i++) {
            GoldrushService.Top t = list.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("&7Gewonnen: &a" + plugin.eco().format(t.money()));
            lore.add("&7Erze: &f" + Text.money(t.ores()));
            List<String> rw = gs.rewardLines(gs.rewardFor(i + 1));
            if (!rw.isEmpty()) {
                lore.add("");
                lore.add("&7Belohnung am Ende:");
                for (String line : rw) lore.add("&8» " + line);
            }
            String name = (i == 0 ? "&e&l" : i == 1 ? "&f&l" : i == 2 ? "&6&l" : "&7") + "#" + (i + 1) + " &f" + t.name() + (t.uuid().equals(me) ? " &8(du)" : "");
            ItemStack head;
            try { head = Items.head(Bukkit.getOfflinePlayer(UUID.fromString(t.uuid())), name, lore); }
            catch (IllegalArgumentException ex) { head = Items.of(Material.PLAYER_HEAD, 1, name, lore); }
            set(TOP_SLOTS[i], head);
        }
        List<String> info = new ArrayList<>();
        info.add("&7Es zaehlt das im Goldrausch");
        info.add("&7gewonnene Geld. " + (gs.active() ? "&7Ende in &f" + TimeUtil.format(gs.left() - gs.left() % 60_000) : gs.everRan() ? "&7Das ist der Endstand." : ""));
        info.add("");
        for (int r = 1; r <= 3; r++) {
            ConfigurationSection rs = gs.rewardFor(r);
            if (rs != null) info.add((r == 1 ? "&e" : r == 2 ? "&f" : "&6") + "Platz " + r + ": &7" + Text.strip(String.join(", ", gs.rewardLines(rs))));
        }
        ConfigurationSection rest = gs.rewardFor(4);
        if (rest != null) info.add("&7Platz 4-10: " + Text.strip(String.join(", ", gs.rewardLines(rest))));
        info.add("");
        info.add("&7Dein Platz: &f" + rankText(gs.rank(p)) + " &8(" + plugin.eco().format(gs.won(p)) + ")");
        set(4, icon("gr_leer", Material.BOOK, "&6Rangliste", info));
    }
}
