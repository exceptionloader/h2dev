package de.minesmp.core.shop;

import de.minesmp.core.MineSMP;
import de.minesmp.core.gui.ConfirmMenu;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Server-Shop (/shop), Verkaufen (/sell, /sellhand, /sellall), Preise aus shop.yml. */
public final class ShopService {
    public record ShopItem(Material material, double buy, double sell, String category) {}
    public record Category(String id, String name, Material icon, int slot, List<ShopItem> items) {}

    private final MineSMP plugin;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Map<Material, ShopItem> byMaterial = new EnumMap<>(Material.class);
    private double confirmAbove;

    public ShopService(MineSMP plugin) {
        this.plugin = plugin;
        reload();
        plugin.command(new SimpleCommand("shop", "minesmp.shop", "Server-Shop", this::shopCmd,
                (s, a) -> a.length == 1 ? new ArrayList<>(categories.keySet()) {{ add("search"); }} : List.of(), "markt", "store"));
        plugin.command(new SimpleCommand("sell", "minesmp.sell", "Items verkaufen", (s, l, a) -> {
            if (s instanceof Player p) new SellMenu(p).open(p);
        }, "verkaufen"));
        plugin.command(new SimpleCommand("sellhand", "minesmp.sell", "Item in der Hand verkaufen", (s, l, a) -> {
            if (s instanceof Player p) sellHand(p);
        }));
        plugin.command(new SimpleCommand("sellall", "minesmp.sell.all", "Gesamtes Inventar verkaufen", (s, l, a) -> {
            if (!(s instanceof Player p)) return;
            ConfirmMenu.open(p, "&8Alles verkaufen?", Items.of(Material.GOLD_INGOT, "&eGesamtes Inventar verkaufen"),
                    List.of("&7Verkauft alle verkaufbaren Items", "&7aus deinem Inventar (ohne Ruestung/Offhand)."),
                    () -> {
                        double total = sellInventory(p, p.getInventory(), true);
                        if (total <= 0) Msg.send(p, "sell.nothing", "{prefix}&cDu hast nichts Verkaufbares dabei.");
                    });
        }));
    }

    public void reload() {
        categories.clear();
        byMaterial.clear();
        YamlConfiguration y = plugin.yaml("shop.yml");
        confirmAbove = y.getDouble("confirm-above", 10_000);
        ConfigurationSection cats = y.getConfigurationSection("categories");
        if (cats == null) return;
        for (String id : cats.getKeys(false)) {
            ConfigurationSection c = cats.getConfigurationSection(id);
            List<ShopItem> items = new ArrayList<>();
            for (Map<?, ?> m : c.getMapList("items")) {
                Material mat = Material.matchMaterial(String.valueOf(m.get("material")));
                if (mat == null || !mat.isItem()) {
                    plugin.getLogger().warning("shop.yml: Unbekanntes Material " + m.get("material"));
                    continue;
                }
                double buy = num(m.get("buy")), sell = num(m.get("sell"));
                if (buy > 0 && sell > buy) {
                    plugin.getLogger().warning("shop.yml: Verkaufspreis > Kaufpreis bei " + mat + " - Verkauf wird auf Kaufpreis begrenzt (Exploit-Schutz).");
                    sell = buy;
                }
                ShopItem si = new ShopItem(mat, buy, sell, id);
                items.add(si);
                byMaterial.putIfAbsent(mat, si);
            }
            Material icon = Material.matchMaterial(c.getString("icon", "CHEST"));
            categories.put(id.toLowerCase(Locale.ROOT), new Category(id, c.getString("name", id), icon == null ? Material.CHEST : icon, c.getInt("slot", -1), items));
        }
    }

    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? 0 : Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return 0; }
    }

    public double sellPrice(Player p, ItemStack it) {
        if (!Items.isPlain(it)) return 0;
        ShopItem si = byMaterial.get(it.getType());
        if (si == null || si.sell <= 0) return 0;
        return si.sell * multiplier(p);
    }

    public double multiplier(Player p) {
        double best = 1.0;
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("economy.sell-multipliers");
        if (s != null) for (String k : s.getKeys(false)) if (p.hasPermission("minesmp.sell.multiplier." + k)) best = Math.max(best, s.getDouble(k));
        return best;
    }

    // ---------------- Befehle / Menues ----------------

    private void shopCmd(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        if (a.length >= 2 && a[0].equalsIgnoreCase("search")) {
            String q = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)).toLowerCase(Locale.ROOT).replace(' ', '_');
            List<ShopItem> found = new ArrayList<>();
            for (ShopItem si : byMaterial.values()) if (si.material.name().toLowerCase(Locale.ROOT).contains(q)) found.add(si);
            openItems(p, new Category("search", "&8Suche: &b" + q, Material.COMPASS, -1, found), 0);
            return;
        }
        if (a.length == 1 && categories.containsKey(a[0].toLowerCase(Locale.ROOT))) {
            openItems(p, categories.get(a[0].toLowerCase(Locale.ROOT)), 0);
            return;
        }
        openMain(p);
    }

    public void openMain(Player p) {
        Menu m = new Menu(5, "&8» &b&lMine&f&lSMP &8Shop") {};
        int auto = 10;
        for (Category c : categories.values()) {
            int slot = c.slot >= 0 ? c.slot : auto++;
            m.set(slot, Items.of(c.icon, 1, c.name, List.of("&7" + c.items.size() + " Items", "", "&bKlicke zum Oeffnen")), e -> openItems(p, c, 0));
        }
        m.set(40, Items.of(Material.HOPPER, 1, "&eVerkaufen", List.of("&7Oeffnet das Verkaufsmenue (&f/sell&7)")), e -> new SellMenu(p).open(p));
        m.set(36, Items.of(Material.OAK_SIGN, 1, "&bSuche", List.of("&7Nutze &f/shop search <Name>")));
        m.set(44, Items.of(Material.GOLD_NUGGET, 1, "&7Kontostand", List.of("&a" + plugin.eco().format(plugin.eco().balance(p)))));
        m.fillEmpty();
        m.open(p);
    }

    private void openItems(Player p, Category c, int page) {
        Menu m = new Menu(6, c.name + " &8(" + (page + 1) + ")") {};
        int per = 45;
        List<ShopItem> items = c.items;
        for (int i = 0; i < per && page * per + i < items.size(); i++) {
            ShopItem si = items.get(page * per + i);
            List<String> lore = new ArrayList<>();
            lore.add(si.buy > 0 ? "&7Kaufen: &a" + plugin.eco().format(si.buy) + " &8(64: " + Text.money(si.buy * 64) + ")" : "&7Kaufen: &cnicht moeglich");
            lore.add(si.sell > 0 ? "&7Verkaufen: &e" + plugin.eco().format(si.sell * multiplier(p)) : "&7Verkaufen: &cnicht moeglich");
            lore.add("");
            if (si.buy > 0) lore.add("&bLinksklick &7» 1 kaufen  &8| &bShift &7» 64 kaufen");
            if (si.sell > 0) lore.add("&eRechtsklick &7» 1 verkaufen  &8| &eShift &7» alle verkaufen");
            m.set(i, Items.of(si.material, 1, "&f" + Text.capitalize(si.material.name()), lore), e -> click(p, si, e.getClick()));
        }
        m.set(49, Items.of(Material.ARROW, "&7Zurueck"), e -> openMain(p));
        if (page > 0) m.set(45, Items.of(Material.SPECTRAL_ARROW, "&bVorherige Seite"), e -> openItems(p, c, page - 1));
        if ((page + 1) * per < items.size()) m.set(53, Items.of(Material.SPECTRAL_ARROW, "&bNaechste Seite"), e -> openItems(p, c, page + 1));
        for (int i = 45; i < 54; i++) if (m.getInventory().getItem(i) == null) m.set(i, Menu.FILLER);
        m.open(p);
    }

    private void click(Player p, ShopItem si, ClickType click) {
        if (!plugin.cooldowns().tryUse(p.getUniqueId(), "shopclick", 150)) return;
        switch (click) {
            case LEFT -> buy(p, si, 1);
            case SHIFT_LEFT -> buy(p, si, si.material.getMaxStackSize());
            case RIGHT -> sellAmount(p, si, 1);
            case SHIFT_RIGHT -> sellAmount(p, si, Integer.MAX_VALUE);
            default -> { }
        }
    }

    private void buy(Player p, ShopItem si, int amount) {
        if (si.buy <= 0) return;
        double price = EconomyRound.r(si.buy * amount);
        Runnable doBuy = () -> {
            ItemStack it = new ItemStack(si.material, amount);
            if (!fits(p.getInventory(), it)) {
                Msg.send(p, "shop.inventory-full", "{prefix}&cDein Inventar ist voll.");
                return;
            }
            if (!plugin.eco().withdraw(p, price, "SHOP_BUY", amount + "x " + si.material)) {
                Msg.send(p, "not-enough-money", "{prefix}&cDu hast nicht genug MineCoins.");
                return;
            }
            p.getInventory().addItem(it);
            Msg.send(p, "shop.bought", "{prefix}&7Gekauft: &f{amount}x {item} &7fuer &a{price}", "amount", String.valueOf(amount),
                    "item", Text.capitalize(si.material.name()), "price", plugin.eco().format(price));
        };
        if (price >= confirmAbove) {
            ConfirmMenu.open(p, "&8Kauf bestaetigen", new ItemStack(si.material, Math.min(amount, 64)),
                    List.of("&7Preis: &a" + plugin.eco().format(price)), doBuy);
        } else doBuy.run();
    }

    private void sellAmount(Player p, ShopItem si, int max) {
        double unit = sellPrice(p, new ItemStack(si.material));
        if (unit <= 0) return;
        ItemStack like = new ItemStack(si.material);
        int have = Items.count(p.getInventory(), like);
        int n = Math.min(have, max);
        if (n <= 0) {
            Msg.send(p, "sell.none-of-item", "{prefix}&cDu hast dieses Item nicht.");
            return;
        }
        like.setAmount(n);
        var notRemoved = p.getInventory().removeItem(like);
        int removed = n - notRemoved.values().stream().mapToInt(ItemStack::getAmount).sum();
        double total = EconomyRound.r(unit * removed);
        plugin.eco().deposit(p, total, "SELL", removed + "x " + si.material);
        Msg.send(p, "sell.sold", "{prefix}&7Verkauft: &f{amount}x {item} &7fuer &a{price}", "amount", String.valueOf(removed),
                "item", Text.capitalize(si.material.name()), "price", plugin.eco().format(total));
    }

    private void sellHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        double unit = sellPrice(p, hand);
        if (unit <= 0) {
            Msg.send(p, "sell.not-sellable", "{prefix}&cDieses Item kann nicht verkauft werden.");
            return;
        }
        int amount = hand.getAmount();
        Material mat = hand.getType();
        p.getInventory().setItemInMainHand(null);
        double total = EconomyRound.r(unit * amount);
        plugin.eco().deposit(p, total, "SELL", amount + "x " + mat);
        Msg.send(p, "sell.sold", "{prefix}&7Verkauft: &f{amount}x {item} &7fuer &a{price}", "amount", String.valueOf(amount),
                "item", Text.capitalize(mat.name()), "price", plugin.eco().format(total));
    }

    /**
     * Verkauft alle verkaufbaren Items eines Inventars (auch Truhen via Verkaufsstab).
     * Items werden zuerst entfernt, dann wird gutgeschrieben (Dupe-Schutz).
     */
    public double sellInventory(Player p, Inventory inv, boolean storageOnly) {
        double total = 0;
        int count = 0;
        ItemStack[] contents = storageOnly && inv instanceof org.bukkit.inventory.PlayerInventory pi ? pi.getStorageContents() : inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            double unit = sellPrice(p, it);
            if (unit <= 0) continue;
            total += unit * it.getAmount();
            count += it.getAmount();
            inv.setItem(i, null);
        }
        total = EconomyRound.r(total);
        if (total > 0) {
            plugin.eco().deposit(p, total, "SELL", count + " Items");
            Msg.send(p, "sell.total", "{prefix}&7Du hast &f{count} Items &7fuer &a{price} &7verkauft.", "count", String.valueOf(count), "price", plugin.eco().format(total));
        }
        return total;
    }

    private static boolean fits(Inventory inv, ItemStack it) {
        int free = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) free += it.getMaxStackSize();
            else if (s.isSimilar(it)) free += Math.max(0, s.getMaxStackSize() - s.getAmount());
            if (free >= it.getAmount()) return true;
        }
        return free >= it.getAmount();
    }

    private static final class EconomyRound {
        static double r(double v) { return Math.floor(v * 100 + 1e-6) / 100.0; }
    }

    /** Verkaufsmenue: Items hineinlegen, beim Schliessen wird verkauft, Rest zurueckgegeben. */
    private final class SellMenu extends Menu {
        private final Player owner;
        private boolean done;

        SellMenu(Player p) {
            super(6, "&8Lege Items zum Verkaufen hinein");
            this.owner = p;
        }

        @Override
        public void handleClick(InventoryClickEvent e) {
            if (done) e.setCancelled(true);
        }

        @Override
        public void handleDrag(InventoryDragEvent e) {
            if (done) e.setCancelled(true);
        }

        @Override
        public void onClose(InventoryCloseEvent e) {
            if (done) return;
            done = true;
            double total = sellInventory(owner, inv, false);
            List<ItemStack> rest = new ArrayList<>();
            for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) rest.add(it);
            inv.clear();
            if (!rest.isEmpty()) {
                Items.giveOrDrop(owner, rest.toArray(new ItemStack[0]));
                Msg.send(owner, "sell.returned", "{prefix}&7Nicht verkaufbare Items wurden zurueckgegeben.");
            }
            if (total <= 0 && rest.isEmpty()) Msg.send(owner, "sell.nothing", "{prefix}&cDu hast nichts Verkaufbares dabei.");
        }
    }
}
