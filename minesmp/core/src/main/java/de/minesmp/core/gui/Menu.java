package de.minesmp.core.gui;

import de.minesmp.core.util.Items;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Basisklasse fuer alle Menues. Standard: Jeder Klick wird abgebrochen (kein Item-Diebstahl),
 * nur registrierte Slot-Handler werden ausgefuehrt.
 */
public abstract class Menu implements InventoryHolder {
    protected final Inventory inv;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();
    public static final ItemStack FILLER = Items.of(Material.GRAY_STAINED_GLASS_PANE, " ");

    protected Menu(int rows, String title) {
        this.inv = Bukkit.createInventory(this, rows * 9, Text.c(title));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inv;
    }

    public void set(int slot, ItemStack item, Consumer<InventoryClickEvent> handler) {
        inv.setItem(slot, item);
        if (handler == null) handlers.remove(slot);
        else handlers.put(slot, handler);
    }

    public void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    public void clear() {
        inv.clear();
        handlers.clear();
    }

    public void fillBorder() {
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            if (i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8) {
                if (inv.getItem(i) == null) inv.setItem(i, FILLER);
            }
        }
    }

    public void fillEmpty() {
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, FILLER);
    }

    public void open(Player p) {
        p.openInventory(inv);
    }

    /** Standardverhalten: alles abbrechen, Handler aufrufen. */
    public void handleClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inv) return;
        Consumer<InventoryClickEvent> h = handlers.get(e.getSlot());
        if (h != null) h.accept(e);
    }

    public void handleDrag(InventoryDragEvent e) {
        for (int slot : e.getRawSlots()) {
            if (slot < inv.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }

    public void onClose(InventoryCloseEvent e) {}

    /** Schliesst das Inventar sicher im naechsten Tick (nicht direkt im Klick-Event). */
    public static void close(org.bukkit.entity.HumanEntity p) {
        Bukkit.getScheduler().runTask(de.minesmp.core.MineSMP.get(), () -> p.closeInventory());
    }
}
