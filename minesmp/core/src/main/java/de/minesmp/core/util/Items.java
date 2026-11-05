package de.minesmp.core.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Item-Builder, Konfigurations-Parser und sichere (De-)Serialisierung. */
public final class Items {
    private Items() {}

    public static ItemStack of(Material m, String name, String... lore) {
        return of(m, 1, name, Arrays.asList(lore));
    }

    public static ItemStack of(Material m, int amount, String name, List<String> lore) {
        ItemStack it = new ItemStack(m, Math.max(1, amount));
        it.editMeta(meta -> {
            if (name != null) meta.displayName(Text.item(name));
            if (lore != null && !lore.isEmpty()) meta.lore(Text.itemLore(lore));
            meta.addItemFlags(ItemFlag.values());
        });
        return it;
    }

    public static ItemStack edit(ItemStack it, Consumer<ItemMeta> c) {
        it.editMeta(c);
        return it;
    }

    public static ItemStack glow(ItemStack it) {
        it.editMeta(m -> m.setEnchantmentGlintOverride(true));
        return it;
    }

    public static ItemStack head(OfflinePlayer p, String name, List<String> lore) {
        ItemStack it = of(Material.PLAYER_HEAD, 1, name, lore);
        it.editMeta(SkullMeta.class, m -> m.setOwningPlayer(p));
        return it;
    }

    public static Enchantment enchant(String name) {
        if (name == null) return null;
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
    }

    /**
     * Liest ein Item aus der Konfiguration:
     * material, amount, name, lore, enchantments: {sharpness: 5}, custom-model-data, glow, unbreakable
     */
    public static ItemStack fromConfig(ConfigurationSection s) {
        if (s == null) return new ItemStack(Material.STONE);
        Material m = Material.matchMaterial(s.getString("material", "STONE"));
        if (m == null || !m.isItem()) m = Material.BARRIER;
        ItemStack it = new ItemStack(m, Math.max(1, s.getInt("amount", 1)));
        it.editMeta(meta -> {
            if (s.isString("name")) meta.displayName(Text.item(s.getString("name")));
            List<String> lore = s.getStringList("lore");
            if (!lore.isEmpty()) meta.lore(Text.itemLore(lore));
            if (s.isInt("custom-model-data")) meta.setCustomModelData(s.getInt("custom-model-data"));
            if (s.getBoolean("glow")) meta.setEnchantmentGlintOverride(true);
            if (s.getBoolean("unbreakable")) meta.setUnbreakable(true);
            ConfigurationSection en = s.getConfigurationSection("enchantments");
            if (en != null) {
                for (String k : en.getKeys(false)) {
                    Enchantment e = enchant(k);
                    if (e == null) continue;
                    if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta book) book.addStoredEnchant(e, en.getInt(k, 1), true);
                    else meta.addEnchant(e, en.getInt(k, 1), true);
                }
            }
        });
        return it;
    }

    public static String toBase64(ItemStack item) {
        if (item == null || item.getType().isAir()) return "";
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    public static ItemStack fromBase64(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
        } catch (Exception e) {
            return null;
        }
    }

    public static String arrayToBase64(ItemStack[] items) {
        List<String> parts = new ArrayList<>();
        for (ItemStack i : items) parts.add(i == null ? "" : toBase64(i));
        return String.join(";", parts);
    }

    public static ItemStack[] arrayFromBase64(String s, int size) {
        ItemStack[] out = new ItemStack[size];
        if (s == null || s.isEmpty()) return out;
        String[] parts = s.split(";", -1);
        for (int i = 0; i < Math.min(size, parts.length); i++) out[i] = fromBase64(parts[i]);
        return out;
    }

    public static String name(ItemStack it) {
        if (it == null) return "Nichts";
        ItemMeta meta = it.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return Text.plain(meta.displayName());
        return Text.capitalize(it.getType().name());
    }

    /** true, wenn das Item exakt ein Vanilla-Item ohne Sonderdaten ist (fuer Verkauf). */
    public static boolean isPlain(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        return it.isSimilar(new ItemStack(it.getType()));
    }

    public static int count(org.bukkit.inventory.Inventory inv, ItemStack like) {
        int n = 0;
        for (ItemStack i : inv.getStorageContents()) if (i != null && i.isSimilar(like)) n += i.getAmount();
        return n;
    }

    /** Fuegt Items hinzu; Rest wird gedroppt. */
    public static void giveOrDrop(org.bukkit.entity.Player p, ItemStack... items) {
        for (ItemStack left : p.getInventory().addItem(items).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }
}
