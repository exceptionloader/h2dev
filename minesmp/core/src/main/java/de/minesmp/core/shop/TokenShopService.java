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
        // Bestehende Server: neue Shops aus Updates (z.B. die Drachen-Schmiede) einmalig ergaenzen
        try (var in = plugin.getResource("token-shops.yml")) {
            if (in == null) return;
            YamlConfiguration def = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            ConfigurationSection shops = def.getConfigurationSection("shops");
            boolean changed = false;
            if (shops != null) for (String id : shops.getKeys(false)) {
                if (cfg.isSet("shops." + id) || cfg.getStringList("removed-shops").contains(id)) continue;
                cfg.set("shops." + id, shops.getConfigurationSection(id));
                changed = true;
                plugin.getLogger().info("token-shops.yml: Shop '" + id + "' ergaenzt.");
            }
            if (changed) cfg.save(new java.io.File(plugin.getDataFolder(), "token-shops.yml"));
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("token-shops.yml: " + e.getMessage());
        }
    }

    public void open(Player p, String shopId) {
        ConfigurationSection shop = cfg.getConfigurationSection("shops." + shopId);
        if (shop == null) { Msg.send(p, "tokenshop.missing", "{prefix}&cDiesen Shop gibt es nicht."); return; }
        String cur = shop.getString("currency", shopId);
        // currency: gems | stars | money (MineCoins, z.B. Drachen-Schmiede)
        boolean money = cur.equalsIgnoreCase("money") || cur.equalsIgnoreCase("coins") || cur.equalsIgnoreCase("geld");
        Kind kind = money ? null : Kind.of(cur);
        if (!money && kind == null) return;
        java.util.function.LongFunction<String> fmt = v -> money ? plugin.eco().format(v) : plugin.tokens().format(kind, v);
        java.util.function.LongPredicate has = v -> money ? plugin.eco().has(p, v) : plugin.tokens().has(p, kind, v);
        String curName = money ? plugin.eco().currency() : kind.display;
        int rows = Math.max(3, Math.min(6, shop.getInt("rows", 5)));
        Menu m = new Menu(rows, shop.getString("title", "&8Shop")) {};
        ConfigurationSection items = shop.getConfigurationSection("items");
        if (items != null) for (String id : items.getKeys(false)) {
            ConfigurationSection it = items.getConfigurationSection(id);
            if (it == null) continue;
            long price = it.getLong("price");
            // icon-custom: das echte Custom Item als Anzeige (mit Verzauberungen und Beschreibung)
            ItemStack custom = it.isString("icon-custom") ? plugin.customItems().create(it.getString("icon-custom")) : null;
            ItemStack icon = custom != null ? custom : Items.fromConfig(it.getConfigurationSection("icon"));
            if (it.getBoolean("display-only")) {
                if (custom == null) icon.editMeta(mm -> mm.addItemFlags(org.bukkit.inventory.ItemFlag.values()));
                m.set(it.getInt("slot", 0), icon);
                continue;
            }
            String perm = it.getString("permission", "");
            icon.editMeta(meta -> {
                List<net.kyori.adventure.text.Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(de.minesmp.core.util.Text.item("&8"));
                lore.add(de.minesmp.core.util.Text.item("&7Preis: " + fmt.apply(price)));
                lore.add(de.minesmp.core.util.Text.item(has.test(price) ? "&a▶ Klicke zum Kaufen" : "&c✖ Nicht genug " + curName));
                meta.lore(lore);
                if (custom == null) meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            });
            m.set(it.getInt("slot", 0), icon, e -> {
                if (!perm.isEmpty() && !p.hasPermission(perm)) { Msg.send(p, "no-permission", "{prefix}&cDazu hast du keine Berechtigung."); return; }
                if (!has.test(price)) {
                    Msg.send(p, "tokens.notenough", "{prefix}&cDu hast nicht genug {name}.", "name", curName);
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1);
                    return;
                }
                ConfirmMenu.open(p, "&8Kaufen?", icon, List.of("&7Preis: " + fmt.apply(price)), () -> {
                    boolean paid = money ? plugin.eco().withdraw(p, price, "SHOP", shopId + ":" + id)
                            : plugin.tokens().take(p, kind, price, "Shop " + shopId + ":" + id);
                    if (!paid) {
                        Msg.send(p, "tokens.notenough", "{prefix}&cDu hast nicht genug {name}.", "name", curName);
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
        if (money) {
            m.set(info, Items.of(Material.GOLD_INGOT, 1, "&6&lDein Guthaben", List.of("&f" + plugin.eco().format(plugin.eco().balance(p)),
                    "&7MineCoins verdienst du mit &f/sell&7,", "&7Auftraegen, Events und im Auktionshaus.")));
            m.fillEmpty();
            m.open(p);
            return;
        }
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
