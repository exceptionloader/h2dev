package de.minesmp.core.world;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Erweiterter Spawn-Schutz: Ohne das Recht minesmp.spawn.build laesst sich am Spawn nichts veraendern -
 * abgebaute Bloecke sind sofort wieder da, Falltueren/Blumentoepfe/Schilder/Rahmen/Betten usw. bleiben wie sie sind,
 * Werkzeuge (Hacke, Schaufel, Axt, Knochenmehl, Feuerzeug ...) wirken nicht, nichts waechst oder zerfaellt.
 * Tueren, Knoepfe, Werkbaenke und Endertruhen bleiben benutzbar.
 *
 * Bau-Modus fuer Builder: Wer den Kreativmodus nur am Spawn nutzen darf (ohne minesmp.creative.free), bekommt dafuer
 * ein leeres Kreativ-Inventar; das eigene Inventar wird gesichert und beim Zurueckschalten oder Verlassen des Spawns
 * wiederhergestellt. So gelangen keine Kreativ-Items in die Survival-Welt.
 */
public final class SpawnProtection implements Listener {
    private static final Set<Material> BLOCKED = EnumSet.of(Material.DAYLIGHT_DETECTOR, Material.REPEATER, Material.COMPARATOR, Material.NOTE_BLOCK,
            Material.JUKEBOX, Material.CAKE, Material.COMPOSTER, Material.LECTERN, Material.CHISELED_BOOKSHELF, Material.BEEHIVE, Material.BEE_NEST,
            Material.RESPAWN_ANCHOR, Material.LEVER, Material.DRAGON_EGG, Material.END_PORTAL_FRAME, Material.SWEET_BERRY_BUSH, Material.CAVE_VINES,
            Material.CAVE_VINES_PLANT, Material.DECORATED_POT, Material.REDSTONE_WIRE, Material.TNT, Material.FLOWER_POT, Material.VAULT,
            Material.TRIAL_SPAWNER, Material.TURTLE_EGG);

    private final MineSMP plugin;

    public SpawnProtection(MineSMP plugin) {
        this.plugin = plugin;
        plugin.listen(this);
    }

    private boolean spawn(org.bukkit.World w) {
        return plugin.spawn() != null && plugin.spawn().isSpawnWorld(w);
    }

    private boolean locked(Player p, org.bukkit.World w) {
        return spawn(w) && !p.hasPermission("minesmp.spawn.build");
    }

    private void hint(Player p) {
        if (plugin.cooldowns().tryUse(p.getUniqueId(), "spawn-protect", 2500))
            Msg.actionbar(p, "spawn.protected", "&cAm Spawn kannst du nichts veraendern.");
    }

    // ================================================================ Bloecke
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakHint(BlockBreakEvent e) {
        if (e.isCancelled() && locked(e.getPlayer(), e.getBlock().getWorld())) hint(e.getPlayer());
    }

    private static boolean blockedBlock(Material m) {
        return BLOCKED.contains(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m) || Tag.FLOWER_POTS.isTagged(m) || Tag.ALL_SIGNS.isTagged(m)
                || Tag.BEDS.isTagged(m) || Tag.CANDLES.isTagged(m) || Tag.CANDLE_CAKES.isTagged(m) || Tag.CAULDRONS.isTagged(m) || Tag.ANVIL.isTagged(m)
                || Tag.CAMPFIRES.isTagged(m);
    }

    /** Items, die beim Rechtsklick auf einen Block die Welt veraendern. */
    private static boolean toolItem(Material m) {
        String n = m.name();
        return n.endsWith("_HOE") || n.endsWith("_SHOVEL") || n.endsWith("_AXE") || n.endsWith("_SPAWN_EGG") || n.endsWith("_BOAT") || n.endsWith("_RAFT")
                || n.endsWith("MINECART") || n.endsWith("_DYE") || n.endsWith("INK_SAC") || n.endsWith("ITEM_FRAME") || n.endsWith("_BUCKET")
                || switch (m) {
                    case SHEARS, BONE_MEAL, FLINT_AND_STEEL, FIRE_CHARGE, ARMOR_STAND, END_CRYSTAL, ENDER_EYE, PAINTING, HONEYCOMB, BRUSH, LEAD,
                         DEBUG_STICK, BUCKET -> true;
                    default -> false;
                };
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || !locked(e.getPlayer(), b.getWorld())) return;
        if (e.getAction() == Action.PHYSICAL) {
            if (b.getType() == Material.TURTLE_EGG || b.getType() == Material.FARMLAND) e.setCancelled(true);
            return;
        }
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (b.getType() == Material.DRAGON_EGG) e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (plugin.crates().isCrateBlock(b)) return;
        if (blockedBlock(b.getType())) {
            e.setCancelled(true);
            hint(e.getPlayer());
            return;
        }
        ItemStack it = e.getItem();
        if (it != null && toolItem(it.getType())) e.setUseItemInHand(Event.Result.DENY);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSign(io.papermc.paper.event.player.PlayerOpenSignEvent e) {
        if (locked(e.getPlayer(), e.getSign().getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (!spawn(e.getBlock().getWorld())) return;
        if (e.getEntity() instanceof Player p && p.hasPermission("minesmp.spawn.build")) return;
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent e) {
        if (spawn(e.getBlock().getWorld()) && (e.getPlayer() == null || !e.getPlayer().hasPermission("minesmp.spawn.build"))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) { if (spawn(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent e) { if (spawn(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onStructure(StructureGrowEvent e) {
        if (spawn(e.getWorld()) && (e.getPlayer() == null || !e.getPlayer().hasPermission("minesmp.spawn.build"))) e.setCancelled(true);
    }

    // ================================================================ Rahmen, Ruestungsstaender, Fahrzeuge
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity en = e.getRightClicked();
        if ((en instanceof Hanging || en instanceof ArmorStand) && locked(e.getPlayer(), en.getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageEntity(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        if (victim instanceof Player || victim instanceof Monster || !spawn(victim.getWorld())) return;
        if (!(victim instanceof Hanging || victim instanceof ArmorStand || victim instanceof EnderCrystal || victim instanceof Vehicle)) return;
        Player p = e.getDamager() instanceof Player pl ? pl : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p2 ? p2 : null;
        if (p == null || !p.hasPermission("minesmp.spawn.build")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicle(VehicleDamageEvent e) {
        if (spawn(e.getVehicle().getWorld()) && !(e.getAttacker() instanceof Player p && p.hasPermission("minesmp.spawn.build"))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() != null && locked(e.getPlayer(), e.getEntity().getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        if (e.getPlayer() != null && locked(e.getPlayer(), e.getEntity().getWorld())) e.setCancelled(true);
    }

    // ================================================================ Bau-Modus (Kreativ nur am Spawn, eigenes Inventar gesichert)
    private boolean free(Player p) {
        return p.hasPermission("minesmp.creative.free");
    }

    private void restore(Player p) {
        PlayerData d = plugin.data().get(p);
        if (d.buildInventory == null || d.buildInventory.isEmpty()) return;
        p.getInventory().clear();
        p.getInventory().setContents(Items.arrayFromBase64(d.buildInventory, p.getInventory().getSize()));
        d.buildInventory = "";
        d.dirty = true;
        plugin.data().save(d, p);
        Msg.send(p, "buildmode.off", "{prefix}&7Bau-Modus beendet - dein Inventar ist wieder da.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        if (free(p)) return;
        PlayerData d = plugin.data().get(p);
        if (e.getNewGameMode() == GameMode.CREATIVE) {
            if (!spawn(p.getWorld())) {
                e.setCancelled(true);
                Msg.send(p, "buildmode.only-spawn", "{prefix}&cDen Kreativmodus kannst du nur am Spawn nutzen.");
                return;
            }
            if (d.buildInventory.isEmpty()) {
                d.buildInventory = Items.arrayToBase64(p.getInventory().getContents());
                if (d.buildInventory.isEmpty()) d.buildInventory = ";";
                d.dirty = true;
                plugin.data().save(d, p); // sofort sichern (Absturzschutz)
                p.getInventory().clear();
                Msg.send(p, "buildmode.on", "{prefix}&aBau-Modus: &7Dein Inventar ist gesichert und kommt zurueck, sobald du den Kreativmodus oder den Spawn verlaesst.");
            }
        } else if (p.getGameMode() == GameMode.CREATIVE && !d.buildInventory.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && p.getGameMode() != GameMode.CREATIVE) restore(p); });
        }
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE && !free(p) && !spawn(p.getWorld())) p.setGameMode(GameMode.SURVIVAL);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!plugin.data().get(p).buildInventory.isEmpty()) {
            if (p.getGameMode() == GameMode.CREATIVE) p.setGameMode(GameMode.SURVIVAL);
            restore(p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || free(p)) return;
            if (p.getGameMode() == GameMode.CREATIVE && !spawn(p.getWorld())) p.setGameMode(GameMode.SURVIVAL);
            if (p.getGameMode() != GameMode.CREATIVE) restore(p);
        }, 10);
    }

    private boolean building(Player p) {
        return p.getGameMode() == GameMode.CREATIVE && !free(p);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (building(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !building(p)) return;
        InventoryType t = e.getInventory().getType();
        if (t == InventoryType.CREATIVE || t == InventoryType.PLAYER || t == InventoryType.CRAFTING) return;
        e.setCancelled(true);
        Msg.actionbar(p, "buildmode.no-inventory", "&cIm Bau-Modus kannst du keine Kisten oder Menues oeffnen.");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!building(e.getPlayer())) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        if (Set.of("sell", "sellhand", "sellall", "ah", "auction", "auktion", "trade", "handel", "order", "orders", "auftrag", "ec", "enderchest", "pay",
                "clan", "c", "cl", "kit", "kits", "repair",
                "duel", "1v1", "duell", "queue", "q", "sumo", "clanduell", "cd", "liga", "gruft", "pve", "crate", "kiste").contains(cmd)) {
            e.setCancelled(true);
            Msg.send(e.getPlayer(), "buildmode.no-command", "{prefix}&cDieser Befehl ist im Bau-Modus gesperrt.");
        }
    }
}
