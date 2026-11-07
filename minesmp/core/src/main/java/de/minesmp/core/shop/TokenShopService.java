package de.minesmp.core.shop;

import de.minesmp.core.MineSMP;
import de.minesmp.core.economy.TokenService.Kind;
import de.minesmp.core.gui.ConfirmMenu;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Gem-Shop und Sterne-Shop (token-shops.yml). Jeder Artikel hat einen Preis in der Shop-Waehrung
 * und eine Belohnung im Format des RewardService (Befehle, Schluessel, Items, Spawner, Buecher ...).
 */
public final class TokenShopService {
    private final MineSMP plugin;
    private YamlConfiguration cfg;

    public TokenShopService(MineSMP plugin) {
        this.plugin = plugin;
        reload();
        plugin.command(new SimpleCommand("gemshop", "minesmp.tokenshop", "Gem-Shop", (s, l, a) -> { if (s instanceof Player p) open(p, "gems"); }, "gemsshop"));
        plugin.command(new SimpleCommand("sterneshop", "minesmp.tokenshop", "Sterne-Shop", (s, l, a) -> { if (s instanceof Player p) open(p, "stars"); }, "starshop", "sternshop"));
    }

    public void reload() {
        cfg = plugin.yaml("token-shops.yml");
    }

    public void open(Player p, String shopId) {
        ConfigurationSection shop = cfg.getConfigurationSection("shops." + shopId);
        if (shop == null) { Msg.send(p, "tokenshop.missing", "{prefix}&cDiesen Shop gibt es nicht."); return; }
        Kind kind = Kind.of(shop.getString("currency", shopId));
        if (kind == null) return;
        int rows = Math.max(3, Math.min(6, shop.getInt("rows", 5)));
        Menu m = new Menu(rows, shop.getString("title", "&8Shop")) {};
        ConfigurationSection items = shop.getConfigurationSection("items");
        if (items != null) for (String id : items.getKeys(false)) {
            ConfigurationSection it = items.getConfigurationSection(id);
            if (it == null) continue;
            long price = it.getLong("price");
            ItemStack icon = Items.fromConfig(it.getConfigurationSection("icon"));
            String perm = it.getString("permission", "");
            icon.editMeta(meta -> {
                List<net.kyori.adventure.text.Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(de.minesmp.core.util.Text.item("&8"));
                lore.add(de.minesmp.core.util.Text.item("&7Preis: " + plugin.tokens().format(kind, price)));
                lore.add(de.minesmp.core.util.Text.item(plugin.tokens().has(p, kind, price) ? "&a▶ Klicke zum Kaufen" : "&c✖ Nicht genug " + kind.display));
                meta.lore(lore);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            });
            m.set(it.getInt("slot", 0), icon, e -> {
                if (!perm.isEmpty() && !p.hasPermission(perm)) { Msg.send(p, "no-permission", "{prefix}&cDazu hast du keine Berechtigung."); return; }
                if (!plugin.tokens().has(p, kind, price)) {
                    Msg.send(p, "tokens.notenough", "{prefix}&cDu hast nicht genug {name}.", "name", kind.display);
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1);
                    return;
                }
                ConfirmMenu.open(p, "&8Kaufen?", icon, List.of("&7Preis: " + plugin.tokens().format(kind, price)), () -> {
                    if (!plugin.tokens().take(p, kind, price, "Shop " + shopId + ":" + id)) {
                        Msg.send(p, "tokens.notenough", "{prefix}&cDu hast nicht genug {name}.", "name", kind.display);
                        return;
                    }
                    plugin.rewards().give(p, it.getConfigurationSection("reward"), "Shop");
                    plugin.audit().log(p.getName(), "TOKENSHOP", shopId + ":" + id + " " + price);
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.4f);
                    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) open(p, shopId); }, 2L);
                });
            });
        }
        int info = rows * 9 - 5;
        m.set(info, Items.of(kind == Kind.GEMS ? Material.AMETHYST_CLUSTER : Material.NETHER_STAR, 1, kind.color + "&lDein Guthaben",
                List.of("&f" + plugin.tokens().format(kind, plugin.tokens().get(p, kind)),
                        kind == Kind.GEMS ? "&7Gems gibt es im Store, bei Events und Votes." : "&7Sterne bekommst du fuer aktive Spielzeit,", kind == Kind.GEMS ? "&8" + plugin.getConfig().getString("links.store", "") : "&7Quests, Events und Keyalls.")));
        String other = kind == Kind.GEMS ? "stars" : "gems";
        m.set(info - 4, Items.of(kind == Kind.GEMS ? Material.NETHER_STAR : Material.AMETHYST_SHARD, 1,
                kind == Kind.GEMS ? "&e&lSterne-Shop" : "&d&lGem-Shop", List.of("&7Zum anderen Shop wechseln")), e -> open(p, other));
        m.fillEmpty();
        m.open(p);
    }

    public List<String> ids() {
        ConfigurationSection s = cfg.getConfigurationSection("shops");
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }
}
