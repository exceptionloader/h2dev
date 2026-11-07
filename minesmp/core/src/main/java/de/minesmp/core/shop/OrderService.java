package de.minesmp.core.shop;

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
import org.bukkit.Sound;
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

/**
 * Kaufauftraege (/order): Spieler hinterlegen Geld fuer eine Menge eines Items,
 * andere Spieler liefern direkt aus ihrem Inventar und werden sofort bezahlt.
 * Der Auftraggeber holt die gelieferten Items in "Meine Auftraege" ab.
 * Zustand liegt im Speicher (Hauptthread), jede Aenderung wird asynchron in "orders" geschrieben.
 */
public final class OrderService {
    public static final class Order {
        long id;
        UUID owner;
        String ownerName;
        Material material;
        int amount, delivered, collected;
        double price;
        long created, expires;
        String status;

        int remaining() { return amount - delivered; }
        boolean active() { return "ACTIVE".equals(status) && remaining() > 0; }
    }

    private final MineSMP plugin;
    private final Map<Long, Order> orders = new LinkedHashMap<>();

    public OrderService(MineSMP plugin) {
        this.plugin = plugin;
        String ai = plugin.db().isMariaDb() ? "INTEGER PRIMARY KEY AUTO_INCREMENT" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        plugin.db().run(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS orders (id " + ai + ", owner VARCHAR(36) NOT NULL, owner_name VARCHAR(16), material VARCHAR(64) NOT NULL,"
                        + " amount INTEGER NOT NULL, delivered INTEGER NOT NULL DEFAULT 0, collected INTEGER NOT NULL DEFAULT 0, price DOUBLE NOT NULL,"
                        + " created BIGINT NOT NULL, expires BIGINT NOT NULL, status VARCHAR(16) NOT NULL)");
            }
        });
        plugin.db().queryThen(c -> {
            List<Order> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, owner, owner_name, material, amount, delivered, collected, price, created, expires, status FROM orders WHERE status <> 'DONE'");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Order o = new Order();
                    o.id = rs.getLong(1);
                    o.owner = UUID.fromString(rs.getString(2));
                    o.ownerName = rs.getString(3);
                    o.material = Material.matchMaterial(rs.getString(4));
                    o.amount = rs.getInt(5);
                    o.delivered = rs.getInt(6);
                    o.collected = rs.getInt(7);
                    o.price = rs.getDouble(8);
                    o.created = rs.getLong(9);
                    o.expires = rs.getLong(10);
                    o.status = rs.getString(11);
                    if (o.material != null) out.add(o);
                }
            }
            return out;
        }, list -> list.forEach(o -> orders.put(o.id, o)));
        plugin.command(new SimpleCommand("order", "minesmp.orders", "Kaufauftraege", this::cmd, (s, a) -> {
            if (a.length == 1) return List.of("create", "mine");
            if (a.length == 4 && a[0].equalsIgnoreCase("create")) {
                List<String> mats = new ArrayList<>();
                String q = a[3].toUpperCase(Locale.ROOT);
                for (Material m : Material.values()) if (m.isItem() && !m.isLegacy() && m.name().startsWith(q) && mats.size() < 40) mats.add(m.name().toLowerCase(Locale.ROOT));
                return mats;
            }
            return List.of();
        }, "orders", "auftrag", "auftraege"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 20 * 60, 20 * 60);
    }

    private int maxOrders(Player p) {
        int max = plugin.getConfig().getInt("orders.max-per-player", 5);
        for (int i = 50; i > max; i--) if (p.hasPermission("minesmp.orders.limit." + i)) return i;
        return max;
    }

    private void persist(Order o) {
        plugin.db().update("UPDATE orders SET delivered = ?, collected = ?, status = ? WHERE id = ?", o.delivered, o.collected, o.status, o.id);
    }

    private void expire() {
        long now = System.currentTimeMillis();
        for (Order o : orders.values()) {
            if ("ACTIVE".equals(o.status) && o.expires < now) {
                o.status = "EXPIRED";
                refund(o);
                persist(o);
            }
        }
    }

    private void refund(Order o) {
        double back = Math.floor(o.remaining() * o.price * 100) / 100.0;
        if (back > 0) plugin.eco().depositAny(o.owner, back, "ORDER_REFUND", "#" + o.id);
        Player p = Bukkit.getPlayer(o.owner);
        if (p != null && back > 0) Msg.send(p, "order.refund", "{prefix}&7Auftrag &f#{id} &7beendet. Rueckerstattung: &a{money}", "id", String.valueOf(o.id), "money", plugin.eco().format(back));
    }

    private void cleanup(Order o) {
        if (!"ACTIVE".equals(o.status) || o.remaining() <= 0) {
            if (o.collected >= o.delivered) {
                if (!"ACTIVE".equals(o.status) || o.remaining() <= 0) {
                    o.status = "DONE";
                    persist(o);
                    orders.remove(o.id);
                }
            }
        }
    }

    // ---------------- Befehle ----------------

    private void cmd(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        if (a.length >= 1 && a[0].equalsIgnoreCase("mine")) { openMine(p); return; }
        if (a.length >= 1 && a[0].equalsIgnoreCase("create")) {
            if (a.length < 3) {
                Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/order create <Menge> <Preis pro Stueck> [Item]");
                Msg.send(p, "order.create-hint", "&7Ohne Item wird das Item in deiner Hand genommen. Beispiel: &b/order create 640 12 diamond");
                return;
            }
            int amount;
            try { amount = Integer.parseInt(a[1]); } catch (NumberFormatException e) { amount = -1; }
            double price = Text.parseAmount(a[2]);
            Material mat = a.length >= 4 ? Material.matchMaterial(a[3]) : p.getInventory().getItemInMainHand().getType();
            int maxAmount = plugin.getConfig().getInt("orders.max-amount", 100000);
            if (amount <= 0 || amount > maxAmount || price <= 0) { Msg.send(p, "order.invalid", "{prefix}&cMenge (1-{max}) oder Preis ungueltig.", "max", String.valueOf(maxAmount)); return; }
            if (mat == null || mat.isAir() || !mat.isItem() || plugin.getConfig().getStringList("orders.blocked-items").contains(mat.name())) {
                Msg.send(p, "order.item", "{prefix}&cDieses Item kann nicht bestellt werden.");
                return;
            }
            long mine = orders.values().stream().filter(o -> o.owner.equals(p.getUniqueId()) && o.active()).count();
            if (mine >= maxOrders(p)) { Msg.send(p, "order.limit", "{prefix}&cDu hast schon {max} aktive Auftraege.", "max", String.valueOf(maxOrders(p))); return; }
            double total = Math.ceil(amount * price * 100) / 100.0;
            int am = amount;
            Material m = mat;
            ConfirmMenu.open(p, "&8Auftrag erstellen?", Items.of(mat, 1, "&f" + amount + "x " + Text.capitalize(mat.name()), List.of("&7Preis pro Stueck: &a" + plugin.eco().format(price))),
                    List.of("&7Gesamt (wird hinterlegt): &a" + plugin.eco().format(total)), () -> create(p, m, am, price, total));
            return;
        }
        open(p, 0);
    }

    private void create(Player p, Material mat, int amount, double price, double total) {
        if (!plugin.eco().withdraw(p, total, "ORDER_CREATE", amount + "x " + mat.name())) { Msg.send(p, "not-enough-money", "{prefix}&cDu hast nicht genug Geld."); return; }
        Order o = new Order();
        o.owner = p.getUniqueId();
        o.ownerName = p.getName();
        o.material = mat;
        o.amount = amount;
        o.price = price;
        o.created = System.currentTimeMillis();
        o.expires = o.created + plugin.getConfig().getLong("orders.duration-hours", 72) * 3_600_000L;
        o.status = "ACTIVE";
        plugin.db().queryThen(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO orders (owner, owner_name, material, amount, delivered, collected, price, created, expires, status) VALUES (?, ?, ?, ?, 0, 0, ?, ?, ?, 'ACTIVE')",
                    Statement.RETURN_GENERATED_KEYS)) {
                Database.bind(ps, o.owner.toString(), o.ownerName, mat.name(), amount, price, o.created, o.expires);
                ps.executeUpdate();
                return Database.insertId(ps);
            }
        }, id -> {
            o.id = id;
            orders.put(id, o);
            Msg.send(p, "order.created", "{prefix}&aAuftrag &f#{id} &aerstellt: &f{amount}x {item} &afuer &f{price} &a/ Stueck.",
                    "id", String.valueOf(id), "amount", String.valueOf(amount), "item", Text.capitalize(mat.name()), "price", plugin.eco().format(price));
            if (total >= plugin.getConfig().getDouble("orders.announce-above", 100000)) {
                Msg.broadcast("order.announce", "{prefix}&f{name} &7sucht &f{amount}x {item} &7fuer &a{price}&7/Stueck! &b/order", "name", p.getName(),
                        "amount", String.valueOf(amount), "item", Text.capitalize(mat.name()), "price", plugin.eco().format(price));
            }
        });
    }

    // ---------------- Menues ----------------

    public void open(Player p, int page) {
        List<Order> list = new ArrayList<>();
        for (Order o : orders.values()) if (o.active() && o.expires > System.currentTimeMillis()) list.add(o);
        list.sort(Comparator.comparingDouble((Order o) -> -o.price));
        int pages = Math.max(1, (list.size() + 44) / 45);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menu m = new Menu(6, "&8Auftraege &7(" + (pg + 1) + "/" + pages + ")") {};
        for (int i = 0; i < 45 && pg * 45 + i < list.size(); i++) {
            Order o = list.get(pg * 45 + i);
            int have = countPlain(p, o.material);
            List<String> lore = new ArrayList<>(List.of("&7Auftraggeber: &f" + o.ownerName, "&7Preis: &a" + plugin.eco().format(o.price) + " &7/ Stueck",
                    "&7Fortschritt: &f" + Text.money(o.delivered) + "&7/&f" + Text.money(o.amount), Text.progressBar((double) o.delivered / o.amount, 20, "&a|", "&8|"),
                    "&7Endet in: &f" + TimeUtil.format(o.expires - System.currentTimeMillis()), "&8"));
            if (o.owner.equals(p.getUniqueId())) lore.add("&bDein Auftrag");
            else lore.add(have > 0 ? "&e▶ Klicke: &f" + Math.min(have, o.remaining()) + "x liefern &7(&a" + plugin.eco().format(Math.min(have, o.remaining()) * o.price) + "&7)" : "&cDu hast keine davon dabei.");
            m.set(i, Items.of(o.material, 1, "&f" + Text.capitalize(o.material.name()), lore), e -> {
                if (o.owner.equals(p.getUniqueId())) { openMine(p); return; }
                deliver(p, o);
                open(p, pg);
            });
        }
        if (pg > 0) m.set(45, Items.of(Material.ARROW, "&7Zurueck"), e -> open(p, pg - 1));
        if (pg < pages - 1) m.set(53, Items.of(Material.ARROW, "&7Weiter"), e -> open(p, pg + 1));
        m.set(48, Items.of(Material.WRITABLE_BOOK, 1, "&a&lNeuer Auftrag", List.of("&7Halte das Item in der Hand und tippe:", "&b/order create <Menge> <Preis>",
                "&7Oder: &b/order create 640 12 diamond", "&8Das Geld wird beim Erstellen hinterlegt.")), e -> {
            Menu.close(p);
            Msg.send(p, "order.create-hint", "&7Ohne Item wird das Item in deiner Hand genommen. Beispiel: &b/order create 640 12 diamond");
        });
        m.set(50, Items.of(Material.CHEST, 1, "&b&lMeine Auftraege", List.of("&7Gelieferte Items abholen", "&7Auftraege abbrechen")), e -> openMine(p));
        for (int i = 45; i < 54; i++) if (m.getInventory().getItem(i) == null) m.set(i, Menu.FILLER);
        m.open(p);
    }

    private static int countPlain(Player p, Material m) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && it.getType() == m && Items.isPlain(it)) n += it.getAmount();
        return n;
    }

    private void deliver(Player p, Order o) {
        if (!o.active()) { Msg.send(p, "order.closed", "{prefix}&cDieser Auftrag ist nicht mehr aktiv."); return; }
        int n = Math.min(countPlain(p, o.material), o.remaining());
        if (n <= 0) { Msg.send(p, "order.none", "{prefix}&cDu hast keine passenden Items dabei."); return; }
        int left = n;
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != o.material || !Items.isPlain(it)) continue;
            int k = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - k);
            left -= k;
        }
        p.getInventory().setStorageContents(contents);
        int done = n - left;
        double pay = Math.floor(done * o.price * 100) / 100.0;
        o.delivered += done;
        persist(o);
        plugin.eco().deposit(p, pay, "ORDER_DELIVER", "#" + o.id + " " + done + "x");
        Msg.send(p, "order.delivered", "{prefix}&aGeliefert: &f{n}x {item} &a-> &f+{money}", "n", String.valueOf(done), "item", Text.capitalize(o.material.name()), "money", plugin.eco().format(pay));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1.3f);
        Player owner = Bukkit.getPlayer(o.owner);
        if (owner != null) Msg.send(owner, "order.got", "{prefix}&f{name} &7hat &f{n}x {item} &7fuer deinen Auftrag geliefert. &b/order mine",
                "name", p.getName(), "n", String.valueOf(done), "item", Text.capitalize(o.material.name()));
    }

    public void openMine(Player p) {
        Menu m = new Menu(4, "&8Meine Auftraege") {};
        int slot = 0;
        for (Order o : new ArrayList<>(orders.values())) {
            if (!o.owner.equals(p.getUniqueId()) || slot >= 27) continue;
            int ready = o.delivered - o.collected;
            String state = o.active() ? "&aAktiv" : "ACTIVE".equals(o.status) ? "&bErfuellt" : "&7Beendet";
            m.set(slot++, Items.of(o.material, 1, "&f" + Text.capitalize(o.material.name()) + " &8#" + o.id, List.of("&7Status: " + state,
                    "&7Geliefert: &f" + Text.money(o.delivered) + "&7/&f" + Text.money(o.amount), "&7Abholbereit: &e" + Text.money(ready), "&8",
                    ready > 0 ? "&eLinksklick: &7Items abholen" : "&8Nichts zum Abholen", o.active() ? "&cShift-Klick: &7Abbrechen (Rest-Geld zurueck)" : "")), e -> {
                if (e.isShiftClick() && o.active()) {
                    o.status = "CANCELLED";
                    refund(o);
                    persist(o);
                } else collect(p, o);
                cleanup(o);
                openMine(p);
            });
        }
        m.set(31, Items.of(Material.ARROW, "&7Zu allen Auftraegen"), e -> open(p, 0));
        m.fillEmpty();
        m.open(p);
    }

    private void collect(Player p, Order o) {
        int ready = o.delivered - o.collected;
        if (ready <= 0) return;
        int given = 0;
        while (given < ready) {
            int k = Math.min(o.material.getMaxStackSize(), ready - given);
            Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(o.material, k));
            int notFit = left.values().stream().mapToInt(ItemStack::getAmount).sum();
            given += k - notFit;
            if (notFit > 0) break;
        }
        if (given <= 0) { Msg.send(p, "inventory-full", "{prefix}&cDein Inventar ist voll."); return; }
        o.collected += given;
        persist(o);
        Msg.send(p, "order.collected", "{prefix}&a{n}x {item} &aabgeholt.", "n", String.valueOf(given), "item", Text.capitalize(o.material.name()));
    }
}
