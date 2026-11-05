package de.minesmp.core.gui;

import de.minesmp.core.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Generischer Bestaetigungsdialog fuer Kaeufe, Verkaeufe und kritische Aktionen. */
public final class ConfirmMenu extends Menu {
    private boolean used;

    public ConfirmMenu(String title, ItemStack display, List<String> info, Runnable onConfirm, Runnable onCancel) {
        super(3, title);
        ItemStack yes = Items.of(Material.LIME_STAINED_GLASS_PANE, 1, "&a&lBestaetigen", info);
        ItemStack no = Items.of(Material.RED_STAINED_GLASS_PANE, "&c&lAbbrechen");
        for (int s : new int[]{10, 11, 12}) set(s, yes, e -> {
            if (used) return;
            used = true;
            Menu.close(e.getWhoClicked());
            onConfirm.run();
        });
        for (int s : new int[]{14, 15, 16}) set(s, no, e -> {
            if (used) return;
            used = true;
            Menu.close(e.getWhoClicked());
            if (onCancel != null) onCancel.run();
        });
        if (display != null) set(13, display);
        fillEmpty();
    }

    public static void open(Player p, String title, ItemStack display, List<String> info, Runnable onConfirm) {
        new ConfirmMenu(title, display, info, onConfirm, null).open(p);
    }
}
