package de.minesmp.core.cosmetic;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kosmetik: Partikel-Spuren und Haustiere (rein optisch, unverwundbar, ohne KI-Ziele).
 * Freischaltung ueber Berechtigungen minesmp.trail.<id> / minesmp.pet.<id> (z.B. aus Crates/Battlepass/Rang).
 */
public final class CosmeticService implements Listener {
    private record Trail(String id, String name, Particle particle, Material icon) {}
    private record Pet(String id, String name, EntityType type, Material icon) {}

    private final MineSMP plugin;
    private final NamespacedKey petKey;
    private final Map<String, Trail> trails = new LinkedHashMap<>();
    private final Map<String, Pet> pets = new LinkedHashMap<>();
    private final Map<UUID, Entity> activePets = new HashMap<>();

    public CosmeticService(MineSMP plugin) {
        this.plugin = plugin;
        this.petKey = new NamespacedKey(plugin, "pet");
        trails.put("herzen", new Trail("herzen", "&dHerzen", Particle.HEART, Material.PINK_DYE));
        trails.put("flammen", new Trail("flammen", "&6Flammen", Particle.FLAME, Material.BLAZE_POWDER));
        trails.put("noten", new Trail("noten", "&aNoten", Particle.NOTE, Material.NOTE_BLOCK));
        trails.put("magie", new Trail("magie", "&5Magie", Particle.WITCH, Material.AMETHYST_SHARD));
        trails.put("funken", new Trail("funken", "&bFunken", Particle.END_ROD, Material.END_ROD));
        trails.put("seele", new Trail("seele", "&3Seelen", Particle.SOUL_FIRE_FLAME, Material.SOUL_LANTERN));
        pets.put("katze", new Pet("katze", "&fKatze", EntityType.CAT, Material.STRING));
        pets.put("fuchs", new Pet("fuchs", "&6Fuchs", EntityType.FOX, Material.SWEET_BERRIES));
        pets.put("hund", new Pet("hund", "&7Hund", EntityType.WOLF, Material.BONE));
        pets.put("axolotl", new Pet("axolotl", "&dAxolotl", EntityType.AXOLOTL, Material.AXOLOTL_BUCKET));
        pets.put("biene", new Pet("biene", "&eBiene", EntityType.BEE, Material.HONEYCOMB));
        plugin.listen(this);
        plugin.command(new SimpleCommand("cosmetics", "minesmp.cosmetics", "Kosmetik", (s, l, a) -> { if (s instanceof Player p) open(p); }, "kosmetik", "trails", "pets"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 4);
        // Alte Haustiere nach Absturz entfernen
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getWorlds().forEach(w -> w.getEntities().forEach(e -> {
            if (e.getPersistentDataContainer().has(petKey)) e.remove();
        })));
    }

    private void open(Player p) {
        PlayerData d = plugin.data().get(p);
        Menu m = new Menu(4, "&8Kosmetik") {};
        m.set(4, Items.of(Material.BLAZE_POWDER, "&bPartikel-Spuren"));
        int slot = 10;
        for (Trail t : trails.values()) {
            boolean has = p.hasPermission("minesmp.trail." + t.id);
            boolean active = t.id.equals(d.trail);
            var it = Items.of(has ? t.icon : Material.GRAY_DYE, 1, t.name, List.of(has ? (active ? "&aAktiv - Klicke zum Deaktivieren" : "&7Klicke zum Aktivieren") : "&cGesperrt (Crates/Battlepass/Rang)"));
            m.set(slot++, active ? Items.glow(it) : it, e -> {
                if (!has) return;
                d.trail = active ? "" : t.id;
                d.dirty = true;
                open(p);
            });
        }
        m.set(22, Items.of(Material.LEAD, "&bHaustiere"));
        slot = 28;
        for (Pet pet : pets.values()) {
            boolean has = p.hasPermission("minesmp.pet." + pet.id);
            boolean active = pet.id.equals(d.pet);
            var it = Items.of(has ? pet.icon : Material.GRAY_DYE, 1, pet.name, List.of(has ? (active ? "&aAktiv - Klicke zum Entfernen" : "&7Klicke zum Rufen") : "&cGesperrt (Crates/Battlepass/Rang)"));
            m.set(slot++, active ? Items.glow(it) : it, e -> {
                if (!has) return;
                d.pet = active ? "" : pet.id;
                d.dirty = true;
                despawnPet(p);
                if (!d.pet.isEmpty()) spawnPet(p);
                open(p);
            });
        }
        m.fillEmpty();
        m.open(p);
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.data().get(p);
            if (!d.trail.isEmpty() && !p.isInvisible() && !plugin.duels().inDuel(p)) {
                Trail t = trails.get(d.trail);
                if (t != null && p.hasPermission("minesmp.trail." + t.id)) p.getWorld().spawnParticle(t.particle, p.getLocation().add(0, 0.2, 0), 2, 0.25, 0.1, 0.25, 0);
            }
            Entity pet = activePets.get(p.getUniqueId());
            if (pet != null) {
                if (!pet.isValid() || pet.getWorld() != p.getWorld() || pet.getLocation().distanceSquared(p.getLocation()) > 400) {
                    despawnPet(p);
                    spawnPet(p);
                } else if (pet instanceof Mob mob && pet.getLocation().distanceSquared(p.getLocation()) > 9) {
                    mob.getPathfinder().moveTo(p.getLocation(), 1.3);
                }
            }
        }
    }

    private void spawnPet(Player p) {
        PlayerData d = plugin.data().get(p);
        Pet pet = pets.get(d.pet);
        if (pet == null || !p.hasPermission("minesmp.pet." + pet.id) || plugin.duels().inDuel(p)) return;
        Location l = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(-1.5));
        Entity e = p.getWorld().spawnEntity(l, pet.type, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, en -> {
            en.getPersistentDataContainer().set(petKey, PersistentDataType.STRING, p.getUniqueId().toString());
            en.setPersistent(false);
            en.setInvulnerable(true);
            en.setSilent(true);
            en.customName(de.minesmp.core.util.Text.c(pet.name + " &7von " + p.getName()));
            en.setCustomNameVisible(true);
            if (en instanceof Ageable ag) ag.setBaby();
            if (en instanceof LivingEntity le) { le.setCollidable(false); le.setRemoveWhenFarAway(false); }
            if (en instanceof Mob mob) mob.setTarget(null);
        });
        activePets.put(p.getUniqueId(), e);
    }

    private void despawnPet(Player p) {
        Entity e = activePets.remove(p.getUniqueId());
        if (e != null) e.remove();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.data().get(e.getPlayer()).pet.isEmpty()) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (e.getPlayer().isOnline()) spawnPet(e.getPlayer()); }, 40);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { despawnPet(e.getPlayer()); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        if (activePets.containsKey(e.getPlayer().getUniqueId())) { despawnPet(e.getPlayer()); spawnPet(e.getPlayer()); }
    }

    private boolean isPet(Entity e) { return e.getPersistentDataContainer().has(petKey); }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) { if (isPet(e.getEntity())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) { if (isPet(e.getEntity())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (isPet(e.getRightClicked())) {
            e.setCancelled(true);
            Msg.actionbar(e.getPlayer(), "pet.info", "&7Ein kosmetisches Haustier.");
        }
    }

    public void shutdown() {
        activePets.values().forEach(Entity::remove);
        activePets.clear();
    }
}
