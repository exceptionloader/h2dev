package de.minesmp.core.trade;

import de.minesmp.core.MineSMP;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sicherer Spielerhandel (/trade). Beide Seiten muessen bestaetigen, danach 3 Sekunden Countdown.
 * Jede Aenderung setzt die Bestaetigung zurueck. Bei Abbruch/Quit bekommt jeder seine Items zurueck.
 */
public final class TradeService {
    private final MineSMP plugin;
    private final Map<UUID, UUID> requests = new HashMap<>(); // Ziel -> Anfragender
    private final Map<UUID, Long> requestTime = new HashMap<>();
    private final Set<TradeMenu> open = new HashSet<>();

    private static final int[] SIDE_A = slots(0);
    private static final int[] SIDE_B = slots(5);

    private static int[] slots(int col) {
        int[] s = new int[20];
        int i = 0;
        for (int r = 0; r < 5; r++) for (int c = 0; c < 4; c++) s[i++] = r * 9 + col + c;
        return s;
    }

    public TradeService(MineSMP plugin) {
        this.plugin = plugin;
        plugin.command(new SimpleCommand("trade", "minesmp.trade", "Handel mit einem Spieler", this::cmd,
                (s, a) -> a.length == 1 ? null : List.of(), "handel"));
    }

    private void cmd(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        if (a.length == 0) {
            Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/trade <Spieler> &7oder &b/trade accept <Spieler>");
            return;
        }
        if (plugin.combat().isTagged(p)) {
            Msg.send(p, "combat.blocked", "{prefix}&cDas kannst du im Kampf nicht tun.");
            return;
        }
        if (a[0].equalsIgnoreCase("accept") && a.length > 1) {
            Player from = Bukkit.getPlayerExact(a[1]);
            if (from == null || !from.getUniqueId().equals(requests.get(p.getUniqueId()))
                    || System.currentTimeMillis() - requestTime.getOrDefault(p.getUniqueId(), 0L) > 60_000) {
                Msg.send(p, "trade.no-request", "{prefix}&cKeine gueltige Handelsanfrage von diesem Spieler.");
                return;
            }
            requests.remove(p.getUniqueId());
            if (!canTrade(from, p)) return;
            TradeMenu m = new TradeMenu(from, p);
            open.add(m);
            from.openInventory(m.getInventory());
            p.openInventory(m.getInventory());
            return;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null || t.equals(p) || !p.canSee(t)) {
            Msg.send(p, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a[0]);
            return;
        }
        if (!canTrade(p, t)) return;
        if (!plugin.cooldowns().tryUse(p.getUniqueId(), "trade-req", 5000)) return;
        requests.put(t.getUniqueId(), p.getUniqueId());
        requestTime.put(t.getUniqueId(), System.currentTimeMillis());
        Msg.send(p, "trade.sent", "{prefix}&7Handelsanfrage an &f{name} &7gesendet.", "name", t.getName());
        t.sendMessage(Text.c(Msg.get("trade.received", "{prefix}&f{name} &7moechte mit dir handeln. ", "name", p.getName()))
                .append(Text.c("&a&l[ANNEHMEN]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/trade accept " + p.getName()))));
    }

    private boolean canTrade(Player a, Player b) {
        double max = plugin.getConfig().getDouble("trade.max-distance", 15);
        if (!a.getWorld().equals(b.getWorld()) || a.getLocation().distanceSquared(b.getLocation()) > max * max) {
            Msg.send(a, "trade.too-far", "{prefix}&cIhr muesst naeher beieinander stehen (max. {max} Bloecke).", "max", String.valueOf((int) max));
            return false;
        }
        return true;
    }

    public void cancelAll() {
        for (TradeMenu m : new ArrayList<>(open)) m.cancel("Server wird neu gestartet");
    }

    private final class TradeMenu extends Menu {
        private final Player a, b;
        private boolean readyA, readyB, finished;
        private double moneyA, moneyB;
        private BukkitTask countdown;

        TradeMenu(Player a, Player b) {
            super(6, "&8Handel: &b" + a.getName() + " &8| &b" + b.getName());
            this.a = a;
            this.b = b;
            for (int r = 0; r < 6; r++) inv.setItem(r * 9 + 4, FILLER);
            render();
        }

        private void render() {
            inv.setItem(45, Items.of(readyA ? Material.LIME_DYE : Material.GRAY_DYE, 1, (readyA ? "&a&lBereit" : "&7Nicht bereit") + " &8(" + a.getName() + ")", List.of("&7Klicke zum Bestaetigen")));
            inv.setItem(53, Items.of(readyB ? Material.LIME_DYE : Material.GRAY_DYE, 1, (readyB ? "&a&lBereit" : "&7Nicht bereit") + " &8(" + b.getName() + ")", List.of("&7Klicke zum Bestaetigen")));
            List<String> moneyLore = List.of("&7Links: +  &8|  &7Rechts: -");
            inv.setItem(46, Items.of(Material.GOLD_NUGGET, 1, "&e100 MC", moneyLore));
            inv.setItem(47, Items.of(Material.GOLD_INGOT, 1, "&e1.000 MC", moneyLore));
            inv.setItem(48, Items.of(Material.GOLD_BLOCK, 1, "&e10.000 MC", moneyLore));
            inv.setItem(50, Items.of(Material.GOLD_BLOCK, 1, "&e10.000 MC", moneyLore));
            inv.setItem(51, Items.of(Material.GOLD_INGOT, 1, "&e1.000 MC", moneyLore));
            inv.setItem(52, Items.of(Material.GOLD_NUGGET, 1, "&e100 MC", moneyLore));
            inv.setItem(49, Items.of(Material.EMERALD, 1, "&bAngebot", List.of("&f" + a.getName() + "&7: &a" + Text.money(moneyA) + " MC",
                    "&f" + b.getName() + "&7: &a" + Text.money(moneyB) + " MC")));
        }

        private void changed() {
            readyA = readyB = false;
            if (countdown != null) { countdown.cancel(); countdown = null; }
            Bukkit.getScheduler().runTask(plugin, this::render);
        }

        private boolean isSide(int slot, boolean sideA) {
            for (int s : sideA ? SIDE_A : SIDE_B) if (s == slot) return true;
            return false;
        }

        @Override
        public void handleClick(InventoryClickEvent e) {
            if (finished) { e.setCancelled(true); return; }
            boolean isA = e.getWhoClicked().getUniqueId().equals(a.getUniqueId());
            Player who = isA ? a : b;
            InventoryAction act = e.getAction();
            if (act == InventoryAction.COLLECT_TO_CURSOR || act == InventoryAction.HOTBAR_SWAP || e.getClick() == ClickType.NUMBER_KEY
                    || e.getClick() == ClickType.SWAP_OFFHAND || e.getClick() == ClickType.DOUBLE_CLICK) {
                e.setCancelled(true);
                return;
            }
            if (e.getClickedInventory() == inv) {
                int slot = e.getSlot();
                if (slot >= 45) {
                    e.setCancelled(true);
                    handleControl(slot, isA, who, e.isRightClick());
                    return;
                }
                if (!isSide(slot, isA)) { e.setCancelled(true); return; }
                if (act == InventoryAction.MOVE_TO_OTHER_INVENTORY) { changed(); return; } // zurueck ins eigene Inventar
                changed();
                return;
            }
            // Klick im eigenen Inventar
            if (act == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                e.setCancelled(true);
                ItemStack it = e.getCurrentItem();
                if (it == null || it.getType().isAir()) return;
                for (int s : isA ? SIDE_A : SIDE_B) {
                    if (inv.getItem(s) == null) {
                        inv.setItem(s, it.clone());
                        e.setCurrentItem(null);
                        changed();
                        return;
                    }
                }
            }
        }

        private void handleControl(int slot, boolean isA, Player who, boolean right) {
            if ((slot == 45 && isA) || (slot == 53 && !isA)) {
                if (isA) readyA = !readyA; else readyB = !readyB;
                render();
                who.playSound(who.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);
                if (readyA && readyB) startCountdown();
                else if (countdown != null) { countdown.cancel(); countdown = null; }
                return;
            }
            double step = switch (slot) { case 46, 52 -> 100; case 47, 51 -> 1000; case 48, 50 -> 10000; default -> 0; };
            boolean own = isA ? slot >= 46 && slot <= 48 : slot >= 50 && slot <= 52;
            if (step == 0 || !own) return;
            double cur = isA ? moneyA : moneyB;
            double next = right ? Math.max(0, cur - step) : cur + step;
            if (next > plugin.eco().balance(who)) {
                Msg.send(who, "not-enough-money", "{prefix}&cDu hast nicht genug MineCoins.");
                return;
            }
            if (isA) moneyA = next; else moneyB = next;
            changed();
        }

        @Override
        public void handleDrag(InventoryDragEvent e) {
            boolean isA = e.getWhoClicked().getUniqueId().equals(a.getUniqueId());
            for (int raw : e.getRawSlots()) {
                if (raw < inv.getSize() && !isSide(raw, isA)) { e.setCancelled(true); return; }
            }
            if (e.getRawSlots().stream().anyMatch(r -> r < inv.getSize())) changed();
        }

        private void startCountdown() {
            countdown = new org.bukkit.scheduler.BukkitRunnable() {
                int sec = 3;
                @Override public void run() {
                    if (!readyA || !readyB || finished) { cancel(); return; }
                    if (sec == 0) { cancel(); complete(); return; }
                    for (Player p : List.of(a, b)) {
                        Msg.markActionbar(p, 1500);
                        p.sendActionBar(Text.c("&bHandel in &f" + sec + "&b..."));
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1, 1.5f);
                    }
                    sec--;
                }
            }.runTaskTimer(plugin, 0, 20);
        }

        private List<ItemStack> take(int[] side) {
            List<ItemStack> out = new ArrayList<>();
            for (int s : side) {
                ItemStack it = inv.getItem(s);
                if (it != null && !it.getType().isAir()) out.add(it);
                inv.setItem(s, null);
            }
            return out;
        }

        private void complete() {
            if (finished) return;
            if (!a.isOnline() || !b.isOnline()) { cancel("Spieler offline"); return; }
            if (moneyA > plugin.eco().balance(a) || moneyB > plugin.eco().balance(b)) { cancel("Nicht genug Geld"); return; }
            finished = true;
            List<ItemStack> fromA = take(SIDE_A), fromB = take(SIDE_B);
            if (moneyA > 0) plugin.eco().transfer(a, b, moneyA, "TRADE");
            if (moneyB > 0) plugin.eco().transfer(b, a, moneyB, "TRADE");
            open.remove(this);
            Menu.close(a);
            Menu.close(b);
            Items.giveOrDrop(a, fromB.toArray(new ItemStack[0]));
            Items.giveOrDrop(b, fromA.toArray(new ItemStack[0]));
            plugin.audit().log(a.getName(), "TRADE", a.getName() + "<->" + b.getName() + " items " + fromA.size() + "/" + fromB.size() + " money " + moneyA + "/" + moneyB);
            for (Player p : List.of(a, b)) {
                Msg.send(p, "trade.done", "{prefix}&aHandel erfolgreich abgeschlossen!");
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.2f);
            }
        }

        void cancel(String reason) {
            if (finished) return;
            finished = true;
            open.remove(this);
            if (countdown != null) countdown.cancel();
            List<ItemStack> fromA = take(SIDE_A), fromB = take(SIDE_B);
            Items.giveOrDrop(a, fromA.toArray(new ItemStack[0]));
            Items.giveOrDrop(b, fromB.toArray(new ItemStack[0]));
            for (Player p : List.of(a, b)) {
                Bukkit.getScheduler().runTask(plugin, () -> { if (p.getOpenInventory().getTopInventory() == inv) p.closeInventory(); });
                Msg.send(p, "trade.cancelled", "{prefix}&cHandel abgebrochen: &7{reason}", "reason", reason);
            }
        }

        @Override
        public void onClose(InventoryCloseEvent e) {
            if (finished) return;
            // Items sofort zurueckgeben (wichtig beim Verlassen des Servers), Inventare erst im naechsten Tick schliessen
            cancel(e.getPlayer().getName() + " hat den Handel geschlossen");
        }
    }
}
