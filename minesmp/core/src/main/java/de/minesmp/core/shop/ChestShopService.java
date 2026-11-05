package de.minesmp.core.shop;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.Text;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Spieler-Shops per Schild an einer Truhe:
 * Zeile 1: [Shop]   Zeile 2: Menge   Zeile 3: K 100 : V 50 (Kauf/Verkauf, eins davon optional)
 * Item = Item in der Hand beim Erstellen. Truhe kann nur der Besitzer oeffnen; Trichter/Explosionen geschuetzt.
 */
public final class ChestShopService implements Listener {
    record Shop(String loc, UUID owner, String ownerName, ItemStack item, int amount, double buy, double sell) {}

    private final MineSMP plugin;
    private final Map<String, Shop> shops = new HashMap<>(); // Schild-Location -> Shop

    public ChestShopService(MineSMP plugin) {
        this.plugin = plugin;
        try {
            plugin.db().query(c -> {
                Map<String, Shop> out = new HashMap<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT loc, owner, owner_name, item, amount, buy, sell FROM chest_shops");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ItemStack it = Items.fromBase64(rs.getString(4));
                        if (it != null) out.put(rs.getString(1), new Shop(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3), it, rs.getInt(5), rs.getDouble(6), rs.getDouble(7)));
                    }
                }
                return out;
            }).get(30, TimeUnit.SECONDS).forEach(shops::put);
        } catch (Exception e) {
            plugin.getLogger().severe("Spieler-Shops konnten nicht geladen werden: " + e.getMessage());
        }
        plugin.listen(this);
    }

    private static String key(Location l) {
        return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
    }

    private static Block attached(Block sign) {
        if (sign.getBlockData() instanceof WallSign ws) return sign.getRelative(ws.getFacing().getOppositeFace());
        return sign.getRelative(BlockFace.DOWN);
    }

    private Shop shopForContainer(Block b) {
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP}) {
            Shop s = shops.get(key(b.getRelative(f).getLocation()));
            if (s != null && attached(b.getRelative(f)).equals(b)) return s;
        }
        // Doppeltruhe: andere Haelfte pruefen
        if (b.getType() != org.bukkit.Material.CHEST && b.getType() != org.bukkit.Material.TRAPPED_CHEST) return null;
        if (b.getState(false) instanceof Chest ch && ch.getInventory().getHolder() instanceof DoubleChest dc) {
            for (InventoryHolder h : new InventoryHolder[]{dc.getLeftSide(), dc.getRightSide()}) {
                if (h instanceof Chest other && !other.getBlock().equals(b)) {
                    Block ob = other.getBlock();
                    for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP}) {
                        Shop s = shops.get(key(ob.getRelative(f).getLocation()));
                        if (s != null && attached(ob.getRelative(f)).equals(ob)) return s;
                    }
                }
            }
        }
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSign(SignChangeEvent e) {
        String l1 = Text.plain(e.line(0)).trim();
        if (!l1.equalsIgnoreCase("[Shop]")) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("minesmp.playershop.create")) {
            e.setCancelled(true);
            return;
        }
        Block container = attached(e.getBlock());
        if (!(container.getState() instanceof Container)) {
            Msg.send(p, "pshop.no-chest", "{prefix}&cDas Schild muss an einer Truhe/einem Fass haengen.");
            return;
        }
        if (shopForContainer(container) != null) {
            Msg.send(p, "pshop.exists", "{prefix}&cDiese Truhe ist bereits ein Shop.");
            e.setCancelled(true);
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            Msg.send(p, "pshop.hold-item", "{prefix}&cHalte das Item, das du handeln willst, in der Hand.");
            e.setCancelled(true);
            return;
        }
        int amount;
        double buy = 0, sell = 0;
        try {
            amount = Integer.parseInt(Text.plain(e.line(1)).trim());
            String prices = Text.plain(e.line(2)).toUpperCase().replace(" ", "");
            for (String part : prices.split(":")) {
                if (part.startsWith("K")) buy = Double.parseDouble(part.substring(1).replace(",", "."));
                else if (part.startsWith("V")) sell = Double.parseDouble(part.substring(1).replace(",", "."));
            }
        } catch (NumberFormatException ex) {
            amount = -1;
        }
        int maxStack = hand.getMaxStackSize() * 9;
        if (amount < 1 || amount > maxStack || (buy <= 0 && sell <= 0) || buy < 0 || sell < 0 || (buy > 0 && sell > buy)) {
            Msg.send(p, "pshop.format", "{prefix}&cFormat: Zeile1 &f[Shop]&c, Zeile2 &fMenge&c, Zeile3 &fK 100 : V 50 &7(Verkauf <= Kauf)");
            e.setCancelled(true);
            return;
        }
        int max = plugin.getConfig().getInt("player-shops.max-per-player", 20);
        long own = shops.values().stream().filter(s -> s.owner.equals(p.getUniqueId())).count();
        if (own >= max && !p.hasPermission("minesmp.playershop.unlimited")) {
            Msg.send(p, "pshop.limit", "{prefix}&cDu hast bereits &f{max} &cShops.", "max", String.valueOf(max));
            e.setCancelled(true);
            return;
        }
        ItemStack item = hand.asOne();
        String k = key(e.getBlock().getLocation());
        Shop shop = new Shop(k, p.getUniqueId(), p.getName(), item, amount, buy, sell);
        shops.put(k, shop);
        plugin.db().update("REPLACE INTO chest_shops (loc, owner, owner_name, item, amount, buy, sell) VALUES (?,?,?,?,?,?,?)",
                k, p.getUniqueId().toString(), p.getName(), Items.toBase64(item), amount, buy, sell);
        e.line(0, Text.c("&b&l[Shop]"));
        e.line(1, Text.c("&f" + amount + "x " + abbreviate(Items.name(item))));
        e.line(2, Text.c((buy > 0 ? "&aK " + Text.money(buy) : "") + (buy > 0 && sell > 0 ? " &8: " : "") + (sell > 0 ? "&eV " + Text.money(sell) : "")));
        e.line(3, Text.c("&7" + p.getName()));
        Msg.send(p, "pshop.created", "{prefix}&aShop erstellt! &7Rechtsklick (Kunden): kaufen, Linksklick: an dich verkaufen.");
    }

    private static String abbreviate(String s) {
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        Player p = e.getPlayer();
        Shop shop = shops.get(key(b.getLocation()));
        if (shop != null && (e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.LEFT_CLICK_BLOCK)) {
            if (shop.owner.equals(p.getUniqueId())) {
                if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    e.setCancelled(true);
                    Msg.send(p, "pshop.own", "{prefix}&7Dein Shop: &f{amount}x {item}&7. Zum Entfernen Schild abbauen.",
                            "amount", String.valueOf(shop.amount), "item", Items.name(shop.item));
                }
                return;
            }
            e.setCancelled(true);
            if (!plugin.cooldowns().tryUse(p.getUniqueId(), "pshop", 300)) return;
            Block cb = attached(b);
            if (!(cb.getState() instanceof Container cont)) return;
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK) customerBuys(p, shop, cont.getInventory());
            else customerSells(p, shop, cont.getInventory());
            return;
        }
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && b.getState() instanceof Container) {
            Shop s = shopForContainer(b);
            if (s != null && !s.owner.equals(p.getUniqueId()) && !p.hasPermission("minesmp.playershop.admin")) {
                e.setCancelled(true);
                Msg.send(p, "pshop.locked", "{prefix}&cDiese Shop-Truhe gehoert &f{owner}&c.", "owner", s.ownerName);
            }
        }
    }

    /** Kunde kauft vom Shop (Shop muss genug Ware haben). */
    private void customerBuys(Player p, Shop shop, Inventory chest) {
        if (shop.buy <= 0) {
            Msg.send(p, "pshop.no-buy", "{prefix}&cDieser Shop verkauft nicht.");
            return;
        }
        ItemStack like = shop.item.asQuantity(1);
        if (Items.count(chest, like) < shop.amount) {
            Msg.send(p, "pshop.empty", "{prefix}&cDieser Shop ist ausverkauft.");
            return;
        }
        if (!plugin.eco().has(p, shop.buy)) {
            Msg.send(p, "not-enough-money", "{prefix}&cDu hast nicht genug MineCoins.");
            return;
        }
        if (p.getInventory().firstEmpty() == -1) {
            Msg.send(p, "shop.inventory-full", "{prefix}&cDein Inventar ist voll.");
            return;
        }
        var notRemoved = chest.removeItem(shop.item.asQuantity(shop.amount));
        if (!notRemoved.isEmpty()) {
            notRemoved.values().forEach(chest::addItem);
            return;
        }
        plugin.eco().withdraw(p, shop.buy, "PSHOP_BUY", shop.loc);
        plugin.eco().depositAny(shop.owner, shop.buy, "PSHOP_SALE", p.getName() + " @ " + shop.loc);
        Items.giveOrDrop(p, shop.item.asQuantity(shop.amount));
        Msg.send(p, "pshop.bought", "{prefix}&7Gekauft: &f{amount}x {item} &7fuer &a{price}", "amount", String.valueOf(shop.amount),
                "item", Items.name(shop.item), "price", plugin.eco().format(shop.buy));
        Player owner = Bukkit.getPlayer(shop.owner);
        if (owner != null) Msg.send(owner, "pshop.owner-sold", "{prefix}&f{name} &7hat in deinem Shop gekauft: &a+{price}", "name", p.getName(), "price", plugin.eco().format(shop.buy));
    }

    /** Kunde verkauft an den Shop (Besitzer muss genug Geld haben). */
    private void customerSells(Player p, Shop shop, Inventory chest) {
        if (shop.sell <= 0) {
            Msg.send(p, "pshop.no-sell", "{prefix}&cDieser Shop kauft nicht an.");
            return;
        }
        ItemStack like = shop.item.asQuantity(1);
        if (Items.count(p.getInventory(), like) < shop.amount) {
            Msg.send(p, "pshop.not-enough-items", "{prefix}&cDu hast nicht genug von diesem Item.");
            return;
        }
        Player owner = Bukkit.getPlayer(shop.owner);
        if (owner == null) {
            Msg.send(p, "pshop.owner-offline", "{prefix}&cAnkauf nur moeglich, wenn der Besitzer online ist.");
            return;
        }
        if (!plugin.eco().has(owner, shop.sell)) {
            Msg.send(p, "pshop.owner-broke", "{prefix}&cDer Besitzer hat nicht genug Geld.");
            return;
        }
        if (!fits(chest, shop.item.asQuantity(shop.amount))) {
            Msg.send(p, "pshop.full", "{prefix}&cDie Shop-Truhe ist voll.");
            return;
        }
        var notRemoved = p.getInventory().removeItem(shop.item.asQuantity(shop.amount));
        if (!notRemoved.isEmpty()) {
            notRemoved.values().forEach(i -> Items.giveOrDrop(p, i));
            return;
        }
        chest.addItem(shop.item.asQuantity(shop.amount));
        plugin.eco().transfer(owner, p, shop.sell, "PSHOP_SELL");
        Msg.send(p, "pshop.sold", "{prefix}&7Verkauft: &f{amount}x {item} &7fuer &a{price}", "amount", String.valueOf(shop.amount),
                "item", Items.name(shop.item), "price", plugin.eco().format(shop.sell));
    }

    private static boolean fits(Inventory inv, ItemStack it) {
        int free = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) free += it.getMaxStackSize();
            else if (s.isSimilar(it)) free += s.getMaxStackSize() - s.getAmount();
        }
        return free >= it.getAmount();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Shop s = shops.get(key(b.getLocation()));
        if (s == null && b.getState() instanceof Container) s = shopForContainer(b);
        if (s == null) return;
        Player p = e.getPlayer();
        if (!s.owner.equals(p.getUniqueId()) && !p.hasPermission("minesmp.playershop.admin")) {
            e.setCancelled(true);
            Msg.send(p, "pshop.locked", "{prefix}&cDiese Shop-Truhe gehoert &f{owner}&c.", "owner", s.ownerName);
            return;
        }
        shops.remove(s.loc);
        plugin.db().update("DELETE FROM chest_shops WHERE loc = ?", s.loc);
        Msg.send(p, "pshop.removed", "{prefix}&7Shop entfernt.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (shops.isEmpty() || e.getSource().getLocation() == null) return;
        if (shopForContainer(e.getSource().getLocation().getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isProtected);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isProtected);
    }

    private boolean isProtected(Block b) {
        return shops.containsKey(key(b.getLocation())) || shopForContainer(b) != null;
    }

    /** Verhindert, dass Fremde das Shop-Schild bearbeiten. */
    @EventHandler(ignoreCancelled = true)
    public void onSignOpen(PlayerOpenSignEvent e) {
        if (shops.containsKey(key(e.getSign().getLocation()))) e.setCancelled(true);
    }

}
