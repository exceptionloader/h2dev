package de.minesmp.core.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Menu m) {
            try {
                m.handleClick(e);
            } catch (Exception ex) {
                e.setCancelled(true);
                Bukkit.getLogger().warning("[MineSMP] Menue-Fehler: " + ex);
                ex.printStackTrace();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Menu m) m.handleDrag(e);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof Menu m && e.getPlayer() instanceof Player) m.onClose(e);
    }

    /** Trichter duerfen niemals in Menues greifen. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (e.getSource().getHolder(false) instanceof Menu || e.getDestination().getHolder(false) instanceof Menu) e.setCancelled(true);
    }
}
