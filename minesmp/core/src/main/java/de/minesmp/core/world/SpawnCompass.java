package de.minesmp.core.world;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Navigator-Kompass: liegt nur in der Spawn-Welt im letzten Hotbar-Platz (Rechtsklick oeffnet /navi).
 * Verlaesst man den Spawn, verschwindet er wieder. Nicht waehrend Duellen, in der PvP-Arena oder in der Gruft.
 * Ein eigenes Item auf dem Platz wird nur verschoben (nie geloescht) und danach zurueckgelegt.
 */
public final class SpawnCompass implements Listener {
    private final MineSMP plugin;
    private final NamespacedKey key;
    private final Map<UUID, Integer> movedTo = new HashMap<>();

    public SpawnCompass(MineSMP plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "navi_compass");
        plugin.listen(this);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::sync), 40, 20);
    }

    private int slot() {
        return Math.max(1, Math.min(9, plugin.getConfig().getInt("spawn-compass.slot", 9))) - 1;
    }

    public boolean isCompass(ItemStack it) {
        return it != null && it.getType() == Material.COMPASS && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key);
    }

    private ItemStack create() {
        ItemStack it = Items.of(Material.COMPASS, 1, plugin.getConfig().getString("spawn-compass.name", "&b&lNavigator &8┃ &7Rechtsklick"),
                List.of("&7Bringt dich zu allen Bereichen:", "&7Markt, Kisten, Arena, Kolosseum, Gruft ...", "&8Liegt nur am Spawn in deiner Hotbar."));
        it.editMeta(m -> {
            m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            m.setEnchantmentGlintOverride(true);
        });
        return it;
    }

    private boolean wanted(Player p) {
        if (!plugin.getConfig().getBoolean("spawn-compass.enabled", true) || plugin.spawn() == null || !plugin.spawn().isSpawnWorld(p.getWorld())) return false;
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return false;
        if (plugin.duels().inDuel(p) || (plugin.pve() != null && plugin.pve().inRun(p)) || plugin.spawn().isPvpZone(p.getLocation())) return false;
        return plugin.data().get(p).setting("spawn-compass", true);
    }

    public void sync(Player p) {
        PlayerInventory inv = p.getInventory();
        int slot = slot();
        boolean want = wanted(p);
        for (int i = 0; i < inv.getSize(); i++) {
            if (isCompass(inv.getItem(i)) && (!want || i != slot)) inv.setItem(i, null);
        }
        if (isCompass(p.getItemOnCursor())) p.setItemOnCursor(null);
        if (!want) {
            // verschobenes Item wieder an seinen Platz legen
            Integer from = movedTo.remove(p.getUniqueId());
            if (from != null && empty(inv.getItem(slot)) && !empty(inv.getItem(from))) {
                inv.setItem(slot, inv.getItem(from));
                inv.setItem(from, null);
            }
            return;
        }
        ItemStack cur = inv.getItem(slot);
        if (isCompass(cur)) return;
        if (!empty(cur)) {
            int free = -1;
            for (int i = 9; i < 36 && free < 0; i++) if (empty(inv.getItem(i))) free = i;
            for (int i = 0; i < 9 && free < 0; i++) if (i != slot && empty(inv.getItem(i))) free = i;
            if (free < 0) {
                if (plugin.cooldowns().tryUse(p.getUniqueId(), "compass-full", 120_000))
                    Msg.send(p, "compass.full", "{prefix}&7Dein Inventar ist voll - den Navigator oeffnest du mit &b/navi&7.");
                return;
            }
            inv.setItem(free, cur);
            movedTo.put(p.getUniqueId(), free);
        }
        inv.setItem(slot, create());
    }

    private static boolean empty(ItemStack it) {
        return it == null || it.getType().isAir();
    }

    private void soon(Player p) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) sync(p); }, 5);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { soon(e.getPlayer()); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { soon(e.getPlayer()); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) { soon(e.getPlayer()); }

    /** Beim Ausloggen entfernen, damit der Kompass nie mit dem Inventar gespeichert wird. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isCompass(inv.getItem(i))) inv.setItem(i, null);
        Integer from = movedTo.remove(p.getUniqueId());
        if (from != null && empty(inv.getItem(slot())) && !empty(inv.getItem(from))) {
            inv.setItem(slot(), inv.getItem(from));
            inv.setItem(from, null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isCompass(e.getItem())) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        if (plugin.cooldowns().tryUse(e.getPlayer().getUniqueId(), "compass-use", 400)) plugin.spawn().openNavigator(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isCompass(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        boolean hit = isCompass(e.getCurrentItem()) || isCompass(e.getCursor());
        if (e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0) hit |= isCompass(p.getInventory().getItem(e.getHotbarButton()));
        if (e.getClick() == ClickType.SWAP_OFFHAND) hit |= isCompass(p.getInventory().getItemInOffHand());
        if (hit) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (isCompass(e.getOldCursor())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isCompass(e.getMainHandItem()) || isCompass(e.getOffHandItem())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isCompass);
    }

    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) if (isCompass(inv.getItem(i))) inv.setItem(i, null);
        }
    }
}
