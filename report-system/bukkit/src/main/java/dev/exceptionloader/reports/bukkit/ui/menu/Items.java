package dev.exceptionloader.reports.bukkit.ui.menu;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

final class Items {

    private Items() {
    }

    static ItemStack create(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(withoutItalics(name));
            meta.lore(lore.stream().map(Items::withoutItalics).toList());
            meta.addItemFlags(ItemFlag.values());
        });
        return item;
    }

    static ItemStack head(Player player, Component name, List<Component> lore) {
        ItemStack item = create(Material.PLAYER_HEAD, name, lore);
        item.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(player));
        return item;
    }

    static ItemStack head(UUID uuid, String playerName, Component name, List<Component> lore) {
        ItemStack item = create(Material.PLAYER_HEAD, name, lore);
        // profile names follow the Java rules, Bedrock names with spaces or long prefixes are left out
        String profileName = playerName.length() <= 16 && playerName.indexOf(' ') < 0 ? playerName : null;
        item.editMeta(SkullMeta.class, meta -> meta.setPlayerProfile(Bukkit.createProfile(uuid, profileName)));
        return item;
    }

    static Material material(String name, Material fallback) {
        Material material = Material.matchMaterial(name);
        return material != null && material.isItem() && !material.isAir() ? material : fallback;
    }

    private static Component withoutItalics(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
