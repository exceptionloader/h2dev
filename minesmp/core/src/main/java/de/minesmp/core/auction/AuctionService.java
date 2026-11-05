package de.minesmp.core.auction;

import de.minesmp.core.MineSMP;
import de.minesmp.core.db.Database;
import de.minesmp.core.gui.ConfirmMenu;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Auktionshaus mit Festpreis-Angeboten, Gebuehr, Ablaufzeit und Abholfach (Mailbox).
 * Dupe-Schutz: Status wird auf dem Hauptthread sofort umgestellt, bevor Items/Geld bewegt werden.
 */
public final class AuctionService {
    public static final class Listing {
        final long id;
        final UUID seller;
        final String sellerName;
        final ItemStack item;
        final double price;
        final long created, expires;
        String status;

        Listing(long id, UUID seller, String sellerName, ItemStack item, double price, long created, long expires, String status) {
            this.id = id; this.seller = seller; this.sellerName = sellerName; this.item = item; this.price = price;
            this.created = created; this.expires = expires; this.status = status;
        }
    }

    private final MineSMP plugin;
    private final Map<Long, Listing> active = new LinkedHashMap<>();

    public AuctionService(MineSMP plugin) {
        this.plugin = plugin;
        loadActive();
        plugin.command(new SimpleCommand("ah", "minesmp.ah", "Auktionshaus", this::cmd,
                (s, a) -> a.length == 1 ? List.of("sell", "search", "my", "collect") : List.of(), "auktionshaus", "auction"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireTask, 20 * 60, 20 * 60);
    }

    private void loadActive() {
        try {
            List<Listing> list = plugin.db().query(c -> {
                List<Listing> out = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT id, seller, seller_name, item, price, created, expires FROM auctions WHERE status = 'ACTIVE'")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ItemStack it = Items.fromBase64(rs.getString(4));
                            if (it == null) continue;
                            out.add(new Listing(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), it, rs.getDouble(5), rs.getLong(6), rs.getLong(7), "ACTIVE"));
                        }
                    }
                }
                return out;
            }).get(30, TimeUnit.SECONDS);
            for (Listing l : list) active.put(l.id, l);
            plugin.getLogger().info(active.size() + " aktive Auktionen geladen.");
        } catch (Exception e) {
            plugin.getLogger().severe("Auktionen konnten nicht geladen werden: " + e.getMessage());
        }
    }

    private void cmd(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        if (a.length == 0) { open(p, 0, null); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "sell", "verkaufen" -> sell(p, a);
            case "search", "suche" -> open(p, 0, a.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)).toLowerCase(Locale.ROOT) : null);
            case "my", "meine" -> openOwn(p);
            case "collect", "abholen" -> collect(p);
            default -> Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/ah [sell <Preis>|search <Text>|my|collect]");
        }
    }

    private int limit(Player p) {
        int lim = plugin.getConfig().getInt("auction.max-listings", 5);
        for (int i = 50; i > lim; i--) if (p.hasPermission("minesmp.ah.limit." + i)) return i;
        return lim;
    }

    private void sell(Player p, String[] a) {
        if (a.length < 2) {
            Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/ah sell <Preis>");
            return;
        }
        double price = Text.parseAmount(a[1]);
        double min = plugin.getConfig().getDouble("auction.min-price", 1), max = plugin.getConfig().getDouble("auction.max-price", 1_000_000_000);
        if (price < min || price > max) {
            Msg.send(p, "ah.bad-price", "{prefix}&cPreis muss zwischen &f{min} &cund &f{max} &cliegen.", "min", Text.money(min), "max", Text.money(max));
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            Msg.send(p, "ah.no-item", "{prefix}&cHalte das Item, das du verkaufen willst, in der Hand.");
            return;
        }
        if (plugin.getConfig().getStringList("auction.blocked-items").contains(hand.getType().name())) {
            Msg.send(p, "ah.blocked", "{prefix}&cDieses Item darf nicht versteigert werden.");
            return;
        }
        long own = active.values().stream().filter(x -> x.seller.equals(p.getUniqueId())).count();
        if (own >= limit(p)) {
            Msg.send(p, "ah.limit", "{prefix}&cDu hast bereits &f{limit} &cAngebote.", "limit", String.valueOf(limit(p)));
            return;
        }
        double fee = Math.floor(price * plugin.getConfig().getDouble("auction.listing-fee-percent", 2) + 0.5) / 100.0;
        if (fee > 0 && !plugin.eco().withdraw(p, fee, "AH_FEE", "Einstellgebuehr")) {
            Msg.send(p, "not-enough-money", "{prefix}&cDu hast nicht genug MineCoins.");
            return;
        }
        ItemStack item = hand.clone();
        p.getInventory().setItemInMainHand(null); // zuerst entfernen -> kein Dupe
        long now = System.currentTimeMillis();
        long expires = now + plugin.getConfig().getLong("auction.duration-hours", 48) * 3_600_000L;
        String data = Items.toBase64(item);
        String search = (Items.name(item) + " " + item.getType().name()).toLowerCase(Locale.ROOT);
        UUID seller = p.getUniqueId();
        String name = p.getName();
        plugin.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO auctions (seller, seller_name, item, price, created, expires, status, search) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                Database.bind(ps, seller.toString(), name, data, price, now, expires, "ACTIVE", search.length() > 250 ? search.substring(0, 250) : search);
                ps.executeUpdate();
                return Database.insertId(ps);
            }
        }).whenComplete((id, err) -> plugin.sync(() -> {
            if (err != null || id == null || id < 0) {
                Items.giveOrDrop(p, item);
                Msg.send(p, "ah.error", "{prefix}&cFehler beim Einstellen. Item wurde zurueckgegeben.");
                return;
            }
            active.put(id, new Listing(id, seller, name, item, price, now, expires, "ACTIVE"));
            Msg.send(p, "ah.listed", "{prefix}&aDein Item wurde fuer &f{price} &aeingestellt. &7(Gebuehr: {fee})",
                    "price", plugin.eco().format(price), "fee", plugin.eco().format(fee));
        }));
    }

    public void open(Player p, int page, String filter) {
        List<Listing> list = new ArrayList<>();
        for (Listing l : active.values()) {
            if (!l.status.equals("ACTIVE")) continue;
            if (filter != null && !(Items.name(l.item) + " " + l.item.getType().name()).toLowerCase(Locale.ROOT).replace('_', ' ').contains(filter.replace('_', ' '))) continue;
            list.add(l);
        }
        list.sort(Comparator.comparingLong((Listing x) -> x.created).reversed());
        Menu m = new Menu(6, "&8Auktionshaus" + (filter != null ? " &7(" + filter + ")" : "") + " &8" + (page + 1)) {};
        int per = 45;
        for (int i = 0; i < per && page * per + i < list.size(); i++) {
            Listing l = list.get(page * per + i);
            ItemStack display = l.item.clone();
            display.editMeta(meta -> {
                List<net.kyori.adventure.text.Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Text.item("&8&m                    "));
                lore.add(Text.item("&7Preis: &a" + plugin.eco().format(l.price)));
                lore.add(Text.item("&7Verkaeufer: &f" + l.sellerName));
                lore.add(Text.item("&7Laeuft ab in: &f" + TimeUtil.format(l.expires - System.currentTimeMillis())));
                lore.add(Text.item(""));
                lore.add(Text.item(l.seller.equals(p.getUniqueId()) ? "&eDein Angebot" : "&bKlicke zum Kaufen"));
                meta.lore(lore);
            });
            m.set(i, display, e -> {
                if (l.seller.equals(p.getUniqueId())) return;
                ConfirmMenu.open(p, "&8Kauf bestaetigen", l.item.clone(), List.of("&7Preis: &a" + plugin.eco().format(l.price)), () -> buy(p, l));
            });
        }
        if (page > 0) m.set(45, Items.of(Material.SPECTRAL_ARROW, "&bVorherige Seite"), e -> open(p, page - 1, filter));
        if ((page + 1) * per < list.size()) m.set(53, Items.of(Material.SPECTRAL_ARROW, "&bNaechste Seite"), e -> open(p, page + 1, filter));
        m.set(47, Items.of(Material.CHEST, 1, "&eMeine Angebote", List.of("&7Eigene Angebote verwalten")), e -> openOwn(p));
        m.set(48, Items.of(Material.ENDER_CHEST, 1, "&bAbholfach", List.of("&7Gekaufte, abgelaufene und", "&7zurueckgezogene Items abholen")), e -> collect(p));
        m.set(49, Items.of(Material.OAK_SIGN, 1, "&bSuche", List.of("&7/ah search <Text>", "&7Einstellen: &f/ah sell <Preis>")));
        m.set(50, Items.of(Material.GOLD_NUGGET, 1, "&7Kontostand", List.of("&a" + plugin.eco().format(plugin.eco().balance(p)))));
        m.set(51, Items.of(Material.CLOCK, "&7Aktualisieren"), e -> open(p, page, filter));
        for (int i = 45; i < 54; i++) if (m.getInventory().getItem(i) == null) m.set(i, Menu.FILLER);
        m.open(p);
    }

    private void buy(Player p, Listing l) {
        if (!"ACTIVE".equals(l.status) || l.expires < System.currentTimeMillis()) {
            Msg.send(p, "ah.gone", "{prefix}&cDieses Angebot ist nicht mehr verfuegbar.");
            return;
        }
        if (!plugin.eco().has(p, l.price)) {
            Msg.send(p, "not-enough-money", "{prefix}&cDu hast nicht genug MineCoins.");
            return;
        }
        l.status = "SOLD"; // sofort sperren
        active.remove(l.id);
        plugin.eco().withdraw(p, l.price, "AH_BUY", "Auktion #" + l.id);
        double tax = Math.floor(l.price * plugin.getConfig().getDouble("auction.sale-tax-percent", 5) + 0.5) / 100.0;
        plugin.eco().depositAny(l.seller, l.price - tax, "AH_SALE", "Auktion #" + l.id + " an " + p.getName());
        plugin.db().update("UPDATE auctions SET status = 'SOLD', buyer = ? WHERE id = ?", p.getUniqueId().toString(), l.id);
        var rest = p.getInventory().addItem(l.item.clone());
        for (ItemStack r : rest.values()) mail(p.getUniqueId(), r, "Auktionskauf");
        if (!rest.isEmpty()) Msg.send(p, "ah.to-mailbox", "{prefix}&eInventar voll - Item liegt im Abholfach (&f/ah collect&e).");
        Msg.send(p, "ah.bought", "{prefix}&aDu hast &f{item} &afuer &f{price} &agekauft.", "item", Items.name(l.item), "price", plugin.eco().format(l.price));
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) Msg.send(seller, "ah.sold", "{prefix}&aDein &f{item} &awurde fuer &f{price} &averkauft! &7(Steuer: {tax})",
                "item", Items.name(l.item), "price", plugin.eco().format(l.price), "tax", plugin.eco().format(tax));
    }

    private void openOwn(Player p) {
        Menu m = new Menu(3, "&8Meine Angebote") {};
        int i = 0;
        for (Listing l : new ArrayList<>(active.values())) {
            if (!l.seller.equals(p.getUniqueId()) || i >= 18) continue;
            ItemStack d = l.item.clone();
            d.editMeta(meta -> {
                List<net.kyori.adventure.text.Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Text.item("&7Preis: &a" + plugin.eco().format(l.price)));
                lore.add(Text.item("&cKlicke zum Zurueckziehen"));
                meta.lore(lore);
            });
            m.set(i++, d, e -> {
                if (!"ACTIVE".equals(l.status)) return;
                l.status = "CANCELLED";
                active.remove(l.id);
                plugin.db().update("UPDATE auctions SET status = 'CANCELLED' WHERE id = ?", l.id);
                mail(p.getUniqueId(), l.item, "Zurueckgezogen");
                Msg.send(p, "ah.cancelled", "{prefix}&7Angebot zurueckgezogen. Hole es mit &f/ah collect &7ab.");
                Menu.close(p);
            });
        }
        m.set(22, Items.of(Material.ARROW, "&7Zurueck"), e -> open(p, 0, null));
        m.open(p);
    }

    private void expireTask() {
        long now = System.currentTimeMillis();
        for (Listing l : new ArrayList<>(active.values())) {
            if (l.expires > now || !"ACTIVE".equals(l.status)) continue;
            l.status = "EXPIRED";
            active.remove(l.id);
            plugin.db().update("UPDATE auctions SET status = 'EXPIRED' WHERE id = ?", l.id);
            mail(l.seller, l.item, "Abgelaufen");
            Player s = Bukkit.getPlayer(l.seller);
            if (s != null) Msg.send(s, "ah.expired", "{prefix}&eDein Angebot &f{item} &eist abgelaufen (&f/ah collect&e).", "item", Items.name(l.item));
        }
    }

    // ---------------- Abholfach ----------------

    public void mail(UUID owner, ItemStack item, String reason) {
        if (item == null || item.getType().isAir()) return;
        plugin.db().update("INSERT INTO mailbox (owner, item, created, reason) VALUES (?, ?, ?, ?)", owner.toString(), Items.toBase64(item), System.currentTimeMillis(), reason);
    }

    /** Holt und loescht Items in einem DB-Schritt; Rest bei vollem Inventar wird wieder eingelagert. */
    public void collect(Player p) {
        if (!plugin.cooldowns().tryUse(p.getUniqueId(), "mailbox", 2000)) return;
        String id = p.getUniqueId().toString();
        plugin.db().query(c -> {
            List<ItemStack> items = new ArrayList<>();
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, item FROM mailbox WHERE owner = ? ORDER BY id LIMIT 36")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                        ItemStack it = Items.fromBase64(rs.getString(2));
                        if (it != null) items.add(it);
                    }
                }
            }
            try (PreparedStatement del = c.prepareStatement("DELETE FROM mailbox WHERE id = ?")) {
                for (long x : ids) { del.setLong(1, x); del.addBatch(); }
                del.executeBatch();
            }
            return items;
        }).whenComplete((items, err) -> plugin.sync(() -> {
            if (err != null) {
                Msg.send(p, "ah.error-collect", "{prefix}&cFehler beim Abholen.");
                return;
            }
            if (items.isEmpty()) {
                Msg.send(p, "ah.mailbox-empty", "{prefix}&7Dein Abholfach ist leer.");
                return;
            }
            if (!p.isOnline()) {
                for (ItemStack it : items) mail(p.getUniqueId(), it, "Rueckgabe");
                return;
            }
            int got = 0;
            for (ItemStack it : items) {
                var rest = p.getInventory().addItem(it);
                if (rest.isEmpty()) got++;
                else rest.values().forEach(r -> mail(p.getUniqueId(), r, "Rueckgabe"));
            }
            Msg.send(p, "ah.collected", "{prefix}&a{count} Item(s) abgeholt.{rest}", "count", String.valueOf(got),
                    "rest", got < items.size() ? " &e(Inventar voll - Rest bleibt im Abholfach)" : "");
        }));
    }
}
