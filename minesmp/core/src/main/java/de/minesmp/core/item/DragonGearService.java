package de.minesmp.core.item;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drachen-Ausruestung "Obsidian &amp; Sturm" (Items in custom-items.yml, 3D-Modelle im Ressourcenpaket unter drachen:*).
 * Faehigkeiten (ability):
 *   DRACHEN_DONNER   Schwert: Blitz-Effekt + Zusatzschaden (Abklingzeit)
 *   DRACHEN_FROST    Bogen: Pfeiltreffer verlangsamen (Abklingzeit)
 *   DRACHEN_PICKE    3x3-Abbau von Stein/Erzen + Eisen/Gold/Kupfer schmelzen
 *   DRACHEN_AXT      Schleichen: bis zu 12 verbundene Stammbloecke
 *   DRACHEN_SCHAUFEL 3x3-Abbau von Erde/Sand/Kies
 *   DRACHEN_SICHEL   reife Feldfruechte neu saeen (aus den eigenen Drops)
 *   DRACHEN_HELM/PANZER/BEINSCHUTZ/STIEFEL  Trage-Effekte, 4/4 = Feuerresistenz + Resistenz I
 *   DRACHEN_KERN     Zutat fuer die Rezepte (wird nicht wie eine Feuerkugel verbraucht)
 * Alle Zusatzbloecke laufen ueber Player#breakBlock: Claims, Spawnschutz und WorldGuard greifen wie beim normalen Abbau.
 * Einstellungen: config.yml -> drachen. Kaufen: /schmiede, gewinnen: Boss-Event (Belohnung "drachen-loot").
 */
public final class DragonGearService implements Listener {
    public static final List<String> GEAR = List.of("drachen_donnerklinge", "drachen_frostbogen", "titan_spitzhacke", "sturm_axt",
            "terra_schaufel", "ernte_sichel", "drachen_helm", "drachen_panzer", "drachen_beinschutz", "drachen_stiefel");

    private static final Set<Material> ROCKS = EnumSet.of(
            Material.STONE, Material.COBBLESTONE, Material.DEEPSLATE, Material.COBBLED_DEEPSLATE,
            Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF,
            Material.CALCITE, Material.NETHERRACK, Material.BLACKSTONE, Material.BASALT,
            Material.SMOOTH_BASALT, Material.END_STONE, Material.DRIPSTONE_BLOCK,
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, Material.IRON_ORE,
            Material.DEEPSLATE_IRON_ORE, Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.REDSTONE_ORE,
            Material.DEEPSLATE_REDSTONE_ORE, Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, Material.EMERALD_ORE,
            Material.DEEPSLATE_EMERALD_ORE, Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE);
    private static final Set<Material> SOIL = EnumSet.of(
            Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY,
            Material.SOUL_SAND, Material.SOUL_SOIL, Material.MUD, Material.MYCELIUM, Material.PODZOL);
    private static final Map<Material, Material> SMELTS = Map.of(
            Material.RAW_IRON, Material.IRON_INGOT,
            Material.RAW_GOLD, Material.GOLD_INGOT,
            Material.RAW_COPPER, Material.COPPER_INGOT);
    private static final Map<Material, Material> SEEDS = Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS);
    private static final List<BlockFace> SIDES = List.of(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);

    private final MineSMP plugin;
    private final NamespacedKey areaOff, smeltOff, thunderUntil, frostUntil, frostArrow;
    private final List<NamespacedKey> recipes = new ArrayList<>();
    private final Set<UUID> extraBreaking = new HashSet<>();
    private final Set<UUID> bonusDamage = new HashSet<>();

    public DragonGearService(MineSMP plugin) {
        this.plugin = plugin;
        areaOff = new NamespacedKey(plugin, "drachen_area_off");
        smeltOff = new NamespacedKey(plugin, "drachen_smelt_off");
        thunderUntil = new NamespacedKey(plugin, "drachen_thunder_until");
        frostUntil = new NamespacedKey(plugin, "drachen_frost_until");
        frostArrow = new NamespacedKey(plugin, "drachen_frost_arrow");
        plugin.listen(this);
        registerRecipes();
        plugin.command(new SimpleCommand("schmiede", "minesmp.drachen", "Drachen-Schmiede", (s, l, a) -> command(s, a),
                (s, a) -> a.length == 1 ? List.of("modus", "schmelzen", "rezepte", "info") : List.of(), "drachenschmiede", "drachen", "drache"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::armorTick, 20L, 20L);
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("drachen");
        return s != null ? s : plugin.getConfig().createSection("drachen");
    }

    private String ability(ItemStack it) {
        String a = plugin.customItems().abilityOf(it);
        return a == null ? "" : a;
    }

    private boolean canUse(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || (p.getGameMode() == GameMode.ADVENTURE && cfg().getBoolean("adventure-mode", false));
    }

    // ================================================================ Befehl /schmiede

    private void command(org.bukkit.command.CommandSender s, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        if (!(s instanceof Player p)) { s.sendMessage("Nur als Spieler."); return; }
        switch (sub) {
            case "modus" -> toggle(p, areaOff, "3x3-Abbau");
            case "schmelzen" -> toggle(p, smeltOff, "Automatisches Schmelzen");
            case "rezepte" -> {
                if (!cfg().getBoolean("recipes", true)) { Msg.send(p, "drachen.no-recipes", "{prefix}&7Drachen-Ausruestung gibt es in der &bDrachen-Schmiede &7und beim &cBoss-Event&7."); return; }
                Msg.send(p, "drachen.recipes", "{prefix}&b&lDrachen-Rezepte\n&8» &fDrachenkern: &7Amethyst - Echo - Amethyst / Echo - Netheritbruchstueck - Echo / Amethyst - Echo - Amethyst\n&8» &fAusruestung: &7Drachenkern + passendes Netherit-Teil (Bogen: normaler Bogen) + Drachenatem, beliebig angeordnet\n&8» &7Verzauberungen und Namen des Netherit-Teils werden durch die festen Werte ersetzt.");
                recipes.forEach(p::discoverRecipe);
            }
            case "info" -> Msg.send(p, "drachen.info", "{prefix}&b&lDrachen-Ausruestung\n&8» &7Kaufen: &f/schmiede\n&8» &7Gewinnen: &cBoss-Event &7(Platz 1 sicher ein Teil, Platz 2-3 mit Glueck)\n&8» &7Rezepte: &f/schmiede rezepte\n&8» &7Ein-/Ausschalten: &f/schmiede modus &7(3x3), &f/schmiede schmelzen");
            default -> {
                if (plugin.tokenShops() != null && plugin.tokenShops().ids().contains("drachen")) plugin.tokenShops().open(p, "drachen");
                else Msg.send(p, "drachen.no-shop", "{prefix}&cDie Drachen-Schmiede ist geschlossen.");
            }
        }
    }

    private boolean off(Player p, NamespacedKey k) {
        return p.getPersistentDataContainer().has(k, PersistentDataType.BYTE);
    }

    private void toggle(Player p, NamespacedKey k, String label) {
        boolean enable = off(p, k);
        if (enable) p.getPersistentDataContainer().remove(k);
        else p.getPersistentDataContainer().set(k, PersistentDataType.BYTE, (byte) 1);
        Msg.send(p, "drachen.toggle", "{prefix}&b{what}: {state}", "what", label, "state", enable ? "&aAN" : "&cAUS");
    }

    // ================================================================ Beute (Boss-Event)

    /** Zufaelliges Teil aus drachen.loot-pool (Gewichte). */
    public ItemStack randomGear() {
        ConfigurationSection pool = cfg().getConfigurationSection("loot-pool");
        Map<String, Integer> weights = new LinkedHashMap<>();
        if (pool != null) for (String k : pool.getKeys(false)) weights.put(k, Math.max(0, pool.getInt(k)));
        if (weights.isEmpty()) GEAR.forEach(g -> weights.put(g, 1));
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) return null;
        int r = ThreadLocalRandom.current().nextInt(total);
        for (var e : weights.entrySet()) {
            r -= e.getValue();
            if (r < 0) return plugin.customItems().create(e.getKey());
        }
        return null;
    }

    /** Belohnung "drachen-loot: <Chance 0-1>": mit dieser Chance ein zufaelliges Teil; gibt das Item zurueck (oder null). */
    public ItemStack rollLoot(Player p, double chance, String reason) {
        if (chance <= 0 || ThreadLocalRandom.current().nextDouble() >= chance) return null;
        ItemStack it = randomGear();
        if (it == null) return null;
        Items.giveOrDrop(p, it);
        if (cfg().getBoolean("broadcast-loot", true))
            Msg.broadcast("drachen.loot", "{prefix}&b&l✦ &f{player} &7hat &f{item} &7erbeutet! &8({reason})", "player", p.getName(), "item", Items.name(it), "reason", reason);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
        plugin.audit().log(p.getName(), "DRACHEN_LOOT", plugin.customItems().idOf(it) + " " + reason);
        return it;
    }

    // ================================================================ Rezepte

    private void registerRecipes() {
        recipes.forEach(Bukkit::removeRecipe);
        recipes.clear();
        if (!cfg().getBoolean("recipes", true)) return;
        ItemStack core = plugin.customItems().create("drachenkern");
        if (core == null) return;
        NamespacedKey ck = new NamespacedKey(plugin, "drachen_kern");
        Bukkit.removeRecipe(ck);
        ShapedRecipe r = new ShapedRecipe(ck, core.clone());
        r.shape("AEA", "ENE", "AEA");
        r.setIngredient('A', Material.AMETHYST_SHARD);
        r.setIngredient('E', Material.ECHO_SHARD);
        r.setIngredient('N', Material.NETHERITE_SCRAP);
        Bukkit.addRecipe(r);
        recipes.add(ck);
        for (String id : GEAR) {
            ItemStack result = plugin.customItems().create(id);
            if (result == null) continue;
            NamespacedKey k = new NamespacedKey(plugin, "drachen_" + id);
            Bukkit.removeRecipe(k);
            ShapelessRecipe sr = new ShapelessRecipe(k, result);
            sr.addIngredient(new RecipeChoice.ExactChoice(core.clone()));
            sr.addIngredient(result.getType());
            sr.addIngredient(Material.DRAGON_BREATH);
            Bukkit.addRecipe(sr);
            recipes.add(k);
        }
    }

    /** Keine Custom Items (ausser dem Kern) verbrauchen und nichts doppelt aufwerten. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof Keyed k) || !recipes.contains(k.getKey())) return;
        for (ItemStack s : e.getInventory().getMatrix()) {
            String id = plugin.customItems().idOf(s);
            if (id != null && !id.equals("drachenkern")) { e.getInventory().setResult(null); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCoreUse(PlayerInteractEvent e) {
        // Der Kern ist eine Feuerkugel, soll aber kein Feuer legen und nicht verbraucht werden
        if (ability(e.getItem()).equals("DRACHEN_KERN")) e.setUseItemInHand(Event.Result.DENY);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCoreDispense(BlockDispenseEvent e) {
        if (ability(e.getItem()).equals("DRACHEN_KERN")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDragonDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof EnderDragon) || !cfg().getBoolean("dragon-drops-core", true)) return;
        ItemStack core = plugin.customItems().create("drachenkern");
        if (core != null) e.getDrops().add(core);
    }

    // ================================================================ Waffen

    private boolean ready(Player p, NamespacedKey k) {
        Long until = p.getPersistentDataContainer().get(k, PersistentDataType.LONG);
        return until == null || System.currentTimeMillis() >= until;
    }

    private void cooldown(Player p, NamespacedKey k, String path, int def) {
        long sec = Math.max(1, Math.min(3600, cfg().getInt(path, def)));
        p.getPersistentDataContainer().set(k, PersistentDataType.LONG, System.currentTimeMillis() + sec * 1000);
    }

    private boolean allowedTarget(LivingEntity t) {
        return !(t instanceof ArmorStand) && (!(t instanceof Player) || cfg().getBoolean("affect-players", false));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity target)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK || e.getFinalDamage() <= 0 || !canUse(p)) return;
        if (!ability(p.getInventory().getItemInMainHand()).equals("DRACHEN_DONNER") || bonusDamage.contains(p.getUniqueId())) return;
        if (!allowedTarget(target) || !ready(p, thunderUntil)) return;
        // Erst nach dem eigentlichen Treffer: der Zusatzschaden ist ein normales Schadensereignis
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || !target.isValid() || target.isDead() || !p.getWorld().equals(target.getWorld())
                    || p.getLocation().distanceSquared(target.getLocation()) > 49
                    || !ability(p.getInventory().getItemInMainHand()).equals("DRACHEN_DONNER") || !ready(p, thunderUntil)) return;
            cooldown(p, thunderUntil, "thunder-cooldown-seconds", 8);
            target.getWorld().strikeLightningEffect(target.getLocation());
            bonusDamage.add(p.getUniqueId());
            try {
                int inv = target.getNoDamageTicks();
                target.setNoDamageTicks(0);
                try { target.damage(Math.max(0, Math.min(40, cfg().getDouble("thunder-bonus-damage", 4))), p); }
                finally { if (!target.isDead()) target.setNoDamageTicks(Math.max(inv, target.getNoDamageTicks())); }
            } finally {
                bonusDamage.remove(p.getUniqueId());
            }
            Msg.actionbar(p, "drachen.thunder", "&b&lDonnerklinge &7- Blitz entfesselt");
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (e.getEntity() instanceof Player p && canUse(p) && ability(e.getBow()).equals("DRACHEN_FROST"))
            e.getProjectile().getPersistentDataContainer().set(frostArrow, PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrost(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof AbstractArrow arrow) || !(arrow.getShooter() instanceof Player p)
                || !(e.getEntity() instanceof LivingEntity target) || e.getFinalDamage() <= 0) return;
        if (!arrow.getPersistentDataContainer().has(frostArrow, PersistentDataType.BYTE) || !allowedTarget(target) || !ready(p, frostUntil)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (target.isDead() || !target.isValid() || !p.isOnline() || !ready(p, frostUntil)) return;
            cooldown(p, frostUntil, "frost-cooldown-seconds", 4);
            int ticks = Math.max(1, Math.min(30, cfg().getInt("frost-duration-seconds", 3))) * 20;
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 1));
            target.getWorld().spawnParticle(Particle.SNOWFLAKE, target.getLocation().add(0, 1, 0), 16, 0.35, 0.6, 0.35, 0.02);
            Msg.actionbar(p, "drachen.frost", "&b&lFrostbogen &7- Langsamkeit II");
        });
    }

    // ================================================================ Werkzeuge

    private static boolean loaded(Block b) {
        return b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!canUse(p) || extraBreaking.contains(p.getUniqueId())) return;
        String ab = ability(p.getInventory().getItemInMainHand());
        if (!ab.startsWith("DRACHEN_")) return;
        Block center = e.getBlock();
        Material type = center.getType();
        List<Block> targets = new ArrayList<>();
        if (ab.equals("DRACHEN_AXT") && p.isSneaking() && Tag.LOGS.isTagged(type) && cfg().getBoolean("tree-felling", true)) {
            int max = Math.max(1, Math.min(32, cfg().getInt("max-tree-blocks", 12)));
            Set<Block> seen = new HashSet<>();
            ArrayDeque<Block> queue = new ArrayDeque<>();
            seen.add(center);
            queue.add(center);
            while (!queue.isEmpty() && targets.size() < max - 1) {
                Block b = queue.remove();
                for (BlockFace f : SIDES) {
                    Block n = b.getRelative(f);
                    if (!loaded(n) || n.getType() != type || n.getLocation().distanceSquared(center.getLocation()) > 100 || !seen.add(n)) continue;
                    targets.add(n);
                    queue.add(n);
                    if (targets.size() >= max - 1) break;
                }
            }
        } else if ((ab.equals("DRACHEN_PICKE") || ab.equals("DRACHEN_SCHAUFEL")) && !p.isSneaking() && !off(p, areaOff) && cfg().getBoolean("area-mining", true)) {
            Set<Material> allowed = ab.equals("DRACHEN_PICKE") ? ROCKS : SOIL;
            if (!allowed.contains(type)) return;
            RayTraceResult hit = p.rayTraceBlocks(6);
            BlockFace face = hit == null ? null : hit.getHitBlockFace();
            if (face == null || face == BlockFace.SELF) face = BlockFace.UP;
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                Block n = face.getModY() != 0 ? center.getRelative(a, 0, b) : face.getModX() != 0 ? center.getRelative(0, a, b) : center.getRelative(a, b, 0);
                if (loaded(n) && allowed.contains(n.getType())) targets.add(n);
            }
        }
        if (targets.isEmpty()) return;
        Map<Block, Material> original = new LinkedHashMap<>();
        targets.forEach(b -> original.put(b, b.getType()));
        // Einen Tick spaeter: erst wenn der eigentliche Abbau wirklich durchging (Claims, Spawnschutz)
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!center.getType().isAir() || !p.isOnline() || !canUse(p) || !ability(p.getInventory().getItemInMainHand()).equals(ab)) return;
            extraBreaking.add(p.getUniqueId());
            try {
                for (var en : original.entrySet()) {
                    Block b = en.getKey();
                    if (!ability(p.getInventory().getItemInMainHand()).equals(ab)) break; // Werkzeug kaputt oder gewechselt
                    if (!loaded(b) || b.getType() != en.getValue() || !p.getWorld().equals(b.getWorld())
                            || p.getEyeLocation().distanceSquared(b.getLocation().add(0.5, 0.5, 0.5)) > 64) continue;
                    p.breakBlock(b); // normales BlockBreakEvent + Haltbarkeit
                }
            } finally {
                extraBreaking.remove(p.getUniqueId());
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        if (!canUse(p)) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        String ab = ability(tool);
        if (ab.equals("DRACHEN_PICKE") && !off(p, smeltOff) && cfg().getBoolean("auto-smelting", true) && !tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
            for (Item dropped : e.getItems()) {
                ItemStack s = dropped.getItemStack();
                Material cooked = SMELTS.get(s.getType());
                if (cooked != null) dropped.setItemStack(s.withType(cooked));
            }
        }
        if (!ab.equals("DRACHEN_SICHEL") || p.isSneaking()) return;
        Material seed = SEEDS.get(e.getBlockState().getType());
        if (seed == null || !(e.getBlockState().getBlockData() instanceof Ageable age) || age.getAge() != age.getMaximumAge()) return;
        // Nach allen anderen Drop-Listenern: einen echten Samen aus den Drops nehmen, nie einen neuen erzeugen
        Bukkit.getScheduler().runTask(plugin, () -> {
            Block soil = e.getBlock();
            if (!soil.getType().isAir() || soil.getRelative(BlockFace.DOWN).getType() != Material.FARMLAND) return;
            for (Item dropped : e.getItems()) {
                if (!dropped.isValid() || dropped.getItemStack().getType() != seed) continue;
                ItemStack st = dropped.getItemStack();
                if (st.getAmount() == 1) dropped.remove();
                else { st.setAmount(st.getAmount() - 1); dropped.setItemStack(st); }
                Ageable fresh = (Ageable) e.getBlockState().getBlockData().clone();
                fresh.setAge(0);
                soil.setBlockData(fresh);
                return;
            }
        });
    }

    // ================================================================ Ruestung

    private static void effect(Player p, PotionEffectType type, int duration, int amp) {
        PotionEffect old = p.getPotionEffect(type);
        if (old == null || old.getAmplifier() < amp || (old.getAmplifier() == amp && old.getDuration() < duration - 20))
            p.addPotionEffect(new PotionEffect(type, duration, amp, true, false, true));
    }

    private void armorTick() {
        ConfigurationSection c = cfg();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) continue;
            PlayerInventory inv = p.getInventory();
            boolean h = ability(inv.getHelmet()).equals("DRACHEN_HELM");
            boolean ch = ability(inv.getChestplate()).equals("DRACHEN_PANZER");
            boolean l = ability(inv.getLeggings()).equals("DRACHEN_BEINSCHUTZ");
            boolean b = ability(inv.getBoots()).equals("DRACHEN_STIEFEL");
            if (h && c.getBoolean("armor.night-vision", true)) effect(p, PotionEffectType.NIGHT_VISION, 260, 0);
            if (b && c.getBoolean("armor.speed", true)) effect(p, PotionEffectType.SPEED, 60, 0);
            if (h && ch && l && b) {
                if (c.getBoolean("armor.full-set-fire-resistance", true)) effect(p, PotionEffectType.FIRE_RESISTANCE, 60, 0);
                if (c.getBoolean("armor.full-set-resistance", true)) effect(p, PotionEffectType.RESISTANCE, 60, 0);
                if (c.getBoolean("armor.particles", true) && !(plugin.vanish() != null && plugin.vanish().isVanished(p)))
                    p.getWorld().spawnParticle(Particle.ENCHANT, p.getLocation().add(0, 0.8, 0), 8, 0.4, 0.6, 0.4, 0.1);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        extraBreaking.remove(e.getPlayer().getUniqueId());
        bonusDamage.remove(e.getPlayer().getUniqueId());
    }
}
