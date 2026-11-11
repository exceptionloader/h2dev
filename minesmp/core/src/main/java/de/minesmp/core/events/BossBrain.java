package de.minesmp.core.events;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Vex;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Die kolossalen Bosse (bosses.yml): Weltenbrand (vierkoepfiger Drache), Malvoria (Riesenhexe), Granitfuerst (Steintitan),
 * Nachtmahr (Schattenkraken) und Glutweberin (Lavenspinne). Jeder Boss ist ein vergroessertes Monster mit eigenen
 * Faehigkeiten; mit BetterModel traegt er sein 3D-Modell. Genutzt vom Boss-Event (events.yml) und frei erweiterbar.
 * Faehigkeiten werden 1 Sekunde vorher angekuendigt (Partikel, Ton, Text), damit man ausweichen kann.
 */
public final class BossBrain implements Listener {
    public record Profile(String id, String name, String model, EntityType entity, double scale, double modelScale, double health,
                          double damage, double melee, double speed, int interval, List<String> abilities, String lore) {}

    private record Ability(String name, Color color) {}

    private static final Map<String, Ability> ABILITIES = Map.ofEntries(
            Map.entry("fire_breath", new Ability("&6Feueratem", Color.ORANGE)),
            Map.entry("poison_breath", new Ability("&aGiftatem", Color.LIME)),
            Map.entry("ice_breath", new Ability("&bEisatem", Color.AQUA)),
            Map.entry("shadow_breath", new Ability("&5Schattenatem", Color.PURPLE)),
            Map.entry("curse", new Ability("&dFluch der Hexe", Color.FUCHSIA)),
            Map.entry("vex", new Ability("&7Totengeister", Color.SILVER)),
            Map.entry("potion_rain", new Ability("&2Giftregen", Color.GREEN)),
            Map.entry("ground_slam", new Ability("&eErdstoss", Color.YELLOW)),
            Map.entry("rock_throw", new Ability("&8Felswurf", Color.GRAY)),
            Map.entry("darkness", new Ability("&8Finsternis", Color.BLACK)),
            Map.entry("tentacle_pull", new Ability("&3Tentakelgriff", Color.TEAL)),
            Map.entry("levitate", new Ability("&fSchwebebann", Color.WHITE)),
            Map.entry("lava_web", new Ability("&cLavanetz", Color.RED)),
            Map.entry("fire_spit", new Ability("&6Glutspeier", Color.ORANGE)),
            Map.entry("spiderlings", new Ability("&4Spinnenbrut", Color.MAROON)));

    private final MineSMP plugin;
    private final NamespacedKey key;
    private final Map<String, Profile> profiles = new LinkedHashMap<>();
    private final List<Fight> fights = new ArrayList<>();
    private final Random random = new Random();

    public BossBrain(MineSMP plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "boss_part");
        reload();
        plugin.listen(this);
    }

    public void reload() {
        File f = new File(plugin.getDataFolder(), "bosses.yml");
        if (!f.exists()) plugin.saveResource("bosses.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        profiles.clear();
        ConfigurationSection s = y.getConfigurationSection("bosses");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection b = s.getConfigurationSection(id);
            if (b == null) continue;
            EntityType type;
            try { type = EntityType.valueOf(b.getString("entity", "RAVAGER").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("bosses.yml: unbekanntes Wesen bei " + id + " - nehme RAVAGER.");
                type = EntityType.RAVAGER;
            }
            List<String> ab = new ArrayList<>();
            for (String a : b.getStringList("abilities")) if (ABILITIES.containsKey(a)) ab.add(a); else plugin.getLogger().warning("bosses.yml: unbekannte Faehigkeit " + a + " bei " + id);
            profiles.put(id.toLowerCase(Locale.ROOT), new Profile(id.toLowerCase(Locale.ROOT), b.getString("name", id), b.getString("model", ""), type,
                    Math.max(0.1, Math.min(16, b.getDouble("scale", 3))), b.getDouble("model-scale", 0.4), b.getDouble("health", 2000), b.getDouble("damage", 14),
                    b.getDouble("melee-damage", b.getDouble("damage", 14) * 0.6), b.getDouble("speed", 1.0), Math.max(3, b.getInt("ability-interval", 8)), ab, b.getString("lore", "")));
        }
    }

    public static List<String> abilityIds() { return ABILITIES.keySet().stream().sorted().toList(); }

    public Profile profile(String id) { return id == null ? null : profiles.get(id.toLowerCase(Locale.ROOT)); }

    public Collection<Profile> profiles() { return profiles.values(); }

    public void shutdown() {
        for (Fight f : new ArrayList<>(fights)) f.end();
    }

    private void mark(Entity e, String what) {
        e.getPersistentDataContainer().set(key, PersistentDataType.STRING, what);
        e.setPersistent(false);
    }

    private String mark(Entity e) {
        return e == null ? null : e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private static void set(LivingEntity le, Attribute a, double v) {
        AttributeInstance i = le.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    /** Ruft den Boss. hpFactor vervielfacht die Lebenspunkte (z.B. nach Spielerzahl). extra: z.B. eigene Markierung des Aufrufers. */
    public Fight spawn(Profile p, Location l, double hpFactor, Consumer<LivingEntity> extra) {
        double wanted = Math.max(20, p.health() * hpFactor);
        // Minecraft erlaubt hoechstens 1024 Leben (spigot.yml): darueber wird eingehender Schaden passend verkleinert
        double hp = Math.min(wanted, healthCap());
        // Gebiet geladen halten, auch wenn noch kein Spieler dort ist (sonst verschwindet der Boss sofort wieder)
        Chunk start = l.getChunk();
        start.addPluginChunkTicket(plugin);
        LivingEntity boss = (LivingEntity) l.getWorld().spawnEntity(l, p.entity(), CreatureSpawnEvent.SpawnReason.CUSTOM, en -> {
            LivingEntity le = (LivingEntity) en;
            mark(le, "boss");
            le.setRemoveWhenFarAway(false);
            set(le, Attribute.MAX_HEALTH, hp);
            le.setHealth(hp);
            set(le, Attribute.SCALE, p.scale());
            set(le, Attribute.ATTACK_DAMAGE, p.melee());
            set(le, Attribute.KNOCKBACK_RESISTANCE, 1);
            set(le, Attribute.FOLLOW_RANGE, 64);
            set(le, Attribute.STEP_HEIGHT, Math.min(10, 1 + p.scale() * 0.4));
            set(le, Attribute.SAFE_FALL_DISTANCE, 64);
            AttributeInstance sp = le.getAttribute(Attribute.MOVEMENT_SPEED);
            if (sp != null) sp.setBaseValue(sp.getBaseValue() * p.speed());
            le.customName(Text.c(p.name()));
            le.setCustomNameVisible(true);
            if (le.getEquipment() != null) le.getEquipment().clear();
            if (extra != null) extra.accept(le);
        });
        Fight f = new Fight(p, boss, hp / wanted, wanted);
        f.ticket = start;
        fights.add(f);
        plugin.models().ensure(boss, p.model(), p.modelScale());
        l.getWorld().playSound(l, Sound.ENTITY_ENDER_DRAGON_GROWL, 3, 0.6f);
        l.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, l.clone().add(0, 2, 0), 2);
        return f;
    }

    /** Ein laufender Bosskampf. tick() jede Sekunde mit der Liste der Kaempfer aufrufen. */
    public final class Fight {
        public final Profile p;
        public final LivingEntity boss;
        private final Set<UUID> parts = new HashSet<>();
        private final Map<Block, BlockData> temp = new LinkedHashMap<>();
        private int cooldown = 6;
        private String last;
        private boolean enraged, ended;
        private final double damageScale, shownMax;
        private Chunk ticket;

        Fight(Profile p, LivingEntity boss, double damageScale, double shownMax) {
            this.p = p;
            this.boss = boss;
            this.damageScale = damageScale;
            this.shownMax = shownMax;
        }

        public boolean enraged() { return enraged; }

        /** Faktor, mit dem eingehender Schaden verkleinert wird (1 = normal). */
        public double damageScale() { return damageScale; }

        /** Leben, wie sie Spieler sehen (eingestellter Wert, auch ueber 1024). */
        public double shownHealth() { return boss.getHealth() / damageScale; }

        public double shownMax() { return shownMax; }

        public void tick(List<Player> fighters) {
            if (ended || !boss.isValid()) return;
            Chunk now = boss.getLocation().getChunk();
            if (!now.equals(ticket)) {
                now.addPluginChunkTicket(plugin);
                if (ticket != null) ticket.removePluginChunkTicket(plugin);
                ticket = now;
            }
            if (!fighters.isEmpty()) plugin.models().ensure(boss, p.model(), p.modelScale());
            List<Player> near = new ArrayList<>();
            for (Player pl : fighters) if (valid(pl) && pl.getWorld().equals(boss.getWorld()) && pl.getLocation().distanceSquared(boss.getLocation()) < 64 * 64) near.add(pl);
            if (near.isEmpty()) return;
            near.sort(Comparator.comparingDouble(pl -> pl.getLocation().distanceSquared(boss.getLocation())));
            if (boss instanceof Mob m && (m.getTarget() == null || !near.contains(m.getTarget()))) m.setTarget(near.get(0));
            AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
            if (!enraged && max != null && boss.getHealth() <= max.getValue() * 0.3) {
                enraged = true;
                AttributeInstance sp = boss.getAttribute(Attribute.MOVEMENT_SPEED);
                if (sp != null) sp.setBaseValue(sp.getBaseValue() * 1.25);
                for (Player pl : near) {
                    pl.showTitle(Title.title(Text.c("&4&lRASEREI"), Text.c(p.name() + " &7wird schneller und staerker!"),
                            Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1800), Duration.ofMillis(400))));
                    pl.playSound(pl.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.2f, 0.6f);
                }
            }
            if (--cooldown > 0 || p.abilities().isEmpty()) return;
            cooldown = enraged ? Math.max(2, (int) Math.round(p.interval() * 0.6)) : p.interval();
            String ab = p.abilities().get(random.nextInt(p.abilities().size()));
            if (ab.equals(last) && p.abilities().size() > 1) ab = p.abilities().get((p.abilities().indexOf(ab) + 1) % p.abilities().size());
            last = ab;
            cast(ab, near);
        }

        /** Faehigkeit sofort ankuendigen und ausloesen (Admin-Befehl). false = unbekannt. */
        public boolean castNow(String ab) {
            if (!ABILITIES.containsKey(ab)) return false;
            if (!boss.isValid()) return true;
            List<Player> near = new ArrayList<>();
            for (Player pl : boss.getWorld().getPlayers()) if (valid(pl) && pl.getLocation().distanceSquared(boss.getLocation()) < 64 * 64) near.add(pl);
            near.sort(Comparator.comparingDouble(pl -> pl.getLocation().distanceSquared(boss.getLocation())));
            if (!near.isEmpty()) cast(ab, near);
            return true;
        }

        /** Ankuendigung, nach 1 Sekunde die Wirkung. */
        void cast(String ab, List<Player> near) {
            Ability a = ABILITIES.get(ab);
            plugin.models().animate(boss, "attack");
            Location c = boss.getLocation();
            for (Player pl : near) {
                Msg.markActionbar(pl, 2200);
                pl.sendActionBar(Text.c(p.name() + " &8» " + a.name() + "&7!"));
            }
            boss.getWorld().spawnParticle(Particle.DUST, c.clone().add(0, boss.getHeight() * 0.6, 0), 60, boss.getWidth() * 0.6, boss.getHeight() * 0.3,
                    boss.getWidth() * 0.6, new Particle.DustOptions(a.color(), 2.2f));
            boss.getWorld().playSound(c, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 2, 0.6f);
            if (ab.equals("ground_slam")) ring(c, 10, a.color());
            List<Player> targets = new ArrayList<>(near);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (ended || !boss.isValid()) return;
                targets.removeIf(pl -> !valid(pl) || !pl.getWorld().equals(boss.getWorld()));
                if (!targets.isEmpty()) run(ab, targets);
            }, 20);
        }

        private void run(String ab, List<Player> near) {
            double d = p.damage();
            switch (ab) {
                case "fire_breath" -> breath(near, Particle.FLAME, pl -> { pl.setFireTicks(Math.max(pl.getFireTicks(), 100)); pl.damage(d * 0.45, boss); }, Sound.ENTITY_BLAZE_SHOOT);
                case "poison_breath" -> breath(near, Particle.ITEM_SLIME, pl -> {
                    pl.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 120, 1));
                    pl.damage(d * 0.2, boss);
                    cloud(pl.getLocation(), PotionEffectType.POISON, Color.LIME);
                }, Sound.ENTITY_LLAMA_SPIT);
                case "ice_breath" -> breath(near, Particle.SNOWFLAKE, pl -> {
                    pl.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 2));
                    pl.setFreezeTicks(Math.max(pl.getFreezeTicks(), 220));
                    pl.damage(d * 0.3, boss);
                }, Sound.BLOCK_GLASS_BREAK);
                case "shadow_breath" -> breath(near, Particle.SQUID_INK, pl -> {
                    pl.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 120, 0));
                    pl.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 80, 0));
                    pl.damage(d * 0.3, boss);
                }, Sound.ENTITY_WITHER_SHOOT);
                case "curse" -> {
                    for (Player pl : within(near, 24)) {
                        pl.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 160, 0));
                        pl.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 120, 0));
                        pl.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 160, 0));
                        pl.getWorld().spawnParticle(Particle.WITCH, pl.getLocation().add(0, 1, 0), 40, 0.6, 1, 0.6, 0.02);
                    }
                    boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 2.5f, 0.6f);
                }
                case "vex" -> minions(EntityType.VEX, 3, near);
                case "potion_rain" -> {
                    for (Player pl : near.subList(0, Math.min(4, near.size()))) {
                        ItemStack it = new ItemStack(Material.SPLASH_POTION);
                        it.editMeta(PotionMeta.class, m -> m.setBasePotionType(random.nextBoolean() ? PotionType.POISON : PotionType.HARMING));
                        ThrownPotion tp = pl.getWorld().spawn(pl.getLocation().add(random.nextDouble() * 2 - 1, 7, random.nextDouble() * 2 - 1), ThrownPotion.class, t -> {
                            t.setItem(it);
                            t.setShooter(boss);
                            mark(t, "part");
                        });
                        tp.setVelocity(new Vector(0, -0.6, 0));
                        parts.add(tp.getUniqueId());
                    }
                    boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WITCH_THROW, 2.5f, 0.6f);
                }
                case "ground_slam" -> {
                    Location c = boss.getLocation();
                    World w = c.getWorld();
                    w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 3, 0.5f);
                    w.spawnParticle(Particle.EXPLOSION, c, 12, 4, 0.3, 4);
                    BlockData stone = Material.STONE.createBlockData();
                    for (int i = 0; i < 48; i++) {
                        double ang = Math.PI * 2 * i / 48;
                        w.spawnParticle(Particle.BLOCK, c.clone().add(Math.cos(ang) * 6, 0.3, Math.sin(ang) * 6), 8, 0.6, 0.2, 0.6, stone);
                    }
                    for (Player pl : within(near, 10)) {
                        Vector away = pl.getLocation().toVector().subtract(c.toVector()).setY(0);
                        if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
                        pl.damage(d * 0.6, boss);
                        pl.setVelocity(away.normalize().multiply(1.5).setY(0.7));
                    }
                }
                case "rock_throw" -> {
                    for (Player pl : near.subList(0, Math.min(2, near.size()))) {
                        Location from = boss.getEyeLocation();
                        Vector to = pl.getLocation().toVector().subtract(from.toVector());
                        double flat = Math.hypot(to.getX(), to.getZ());
                        double t = Math.max(12, flat * 1.1);              // Flugzeit in Ticks
                        Vector v = new Vector(to.getX() / t, (to.getY() + 0.04 * t * t / 2) / t, to.getZ() / t);
                        FallingBlock fb = boss.getWorld().spawn(from, FallingBlock.class, b -> {
                            b.setBlockData(Material.COBBLED_DEEPSLATE.createBlockData());
                            b.setDropItem(false);
                            b.setCancelDrop(true);
                            b.setHurtEntities(false);
                            mark(b, "rock");
                        });
                        fb.setVelocity(v);
                        parts.add(fb.getUniqueId());
                    }
                    boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 2.5f, 0.5f);
                }
                case "darkness" -> {
                    for (Player pl : within(near, 30)) {
                        pl.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 160, 0));
                        pl.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
                    }
                    boss.getWorld().spawnParticle(Particle.SCULK_SOUL, boss.getLocation().add(0, 2, 0), 120, 6, 2, 6, 0.05);
                    boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 2, 0.7f);
                }
                case "tentacle_pull" -> {
                    for (Player pl : within(near, 20).subList(0, Math.min(3, within(near, 20).size()))) {
                        Vector pull = boss.getLocation().toVector().subtract(pl.getLocation().toVector());
                        line(pl.getLocation().add(0, 1, 0), boss.getLocation().add(0, 1.5, 0), Particle.SQUID_INK);
                        pl.damage(d * 0.3, boss);
                        pl.setVelocity(pull.setY(0).normalize().multiply(1.3).setY(0.4));
                    }
                    boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_SQUID_SQUIRT, 2.5f, 0.5f);
                }
                case "levitate" -> {
                    for (Player pl : within(near, 14)) {
                        pl.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 40, 1));
                        pl.getWorld().spawnParticle(Particle.REVERSE_PORTAL, pl.getLocation(), 50, 0.5, 0.2, 0.5, 0.05);
                    }
                    boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_SHULKER_SHOOT, 2.5f, 0.5f);
                }
                case "lava_web" -> {
                    for (Player pl : near.subList(0, Math.min(3, near.size()))) {
                        for (Block b : List.of(pl.getLocation().getBlock(), pl.getLocation().getBlock().getRelative(0, 1, 0))) placeTemp(b, Material.COBWEB);
                        pl.setFireTicks(Math.max(pl.getFireTicks(), 60));
                        pl.getWorld().spawnParticle(Particle.LAVA, pl.getLocation(), 20, 0.6, 0.4, 0.6);
                    }
                    boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 2.5f, 0.5f);
                }
                case "fire_spit" -> {
                    for (Player pl : near.subList(0, Math.min(3, near.size()))) {
                        Location from = boss.getEyeLocation();
                        Vector dir = pl.getEyeLocation().toVector().subtract(from.toVector()).normalize();
                        SmallFireball fb = boss.getWorld().spawn(from.add(dir.clone().multiply(boss.getWidth() * 0.6)), SmallFireball.class, f -> {
                            f.setIsIncendiary(false);
                            f.setYield(0);
                            f.setShooter(boss);
                            mark(f, "part");
                        });
                        fb.setDirection(dir);
                        fb.setVelocity(dir.multiply(1.2));
                        parts.add(fb.getUniqueId());
                    }
                    boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 2.5f, 0.6f);
                }
                case "spiderlings" -> minions(EntityType.CAVE_SPIDER, 4, near);
                default -> { }
            }
        }

        private List<Player> within(List<Player> near, double r) {
            List<Player> out = new ArrayList<>();
            for (Player pl : near) if (pl.getLocation().distanceSquared(boss.getLocation()) <= r * r) out.add(pl);
            return out;
        }

        /** Kegel vom Kopf des Bosses zum naechsten Spieler: alle in Reichweite (18) und Winkel (30 Grad) werden getroffen. */
        private void breath(List<Player> near, Particle particle, Consumer<Player> effect, Sound sound) {
            Player target = near.get(0);
            Location eye = boss.getEyeLocation();
            Vector dir = target.getEyeLocation().toVector().subtract(eye.toVector()).normalize();
            World w = boss.getWorld();
            for (double s = 1; s < 18; s += 0.6) {
                Location at = eye.clone().add(dir.clone().multiply(s));
                double spread = s * 0.18;
                w.spawnParticle(particle, at, 4, spread, spread, spread, 0.01);
            }
            w.playSound(eye, sound, 2.5f, 0.7f);
            w.playSound(eye, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.5f, 1.2f);
            for (Player pl : near) {
                Vector to = pl.getEyeLocation().toVector().subtract(eye.toVector());
                double dist = to.length();
                if (dist > 18 || dist < 0.1) continue;
                if (to.normalize().angle(dir) <= Math.toRadians(30) || dist < boss.getWidth() * 0.8) effect.accept(pl);
            }
        }

        private void cloud(Location l, PotionEffectType type, Color color) {
            AreaEffectCloud c = l.getWorld().spawn(l, AreaEffectCloud.class, a -> {
                a.setRadius(3);
                a.setDuration(120);
                a.setRadiusPerTick(-0.01f);
                a.setColor(color);
                a.addCustomEffect(new PotionEffect(type, 60, 0), true);
                a.setSource(boss);
                mark(a, "part");
            });
            parts.add(c.getUniqueId());
        }

        private void minions(EntityType type, int n, List<Player> near) {
            int alive = 0;
            for (UUID id : parts) if (Bukkit.getEntity(id) instanceof Mob m && m.isValid()) alive++;
            int max = plugin.getConfig().getInt("bosses.max-minions", 8);
            for (int i = 0; i < n && alive < max; i++, alive++) {
                Location l = boss.getLocation().add(random.nextDouble() * 6 - 3, 0.5, random.nextDouble() * 6 - 3);
                Entity e = boss.getWorld().spawnEntity(l, type, CreatureSpawnEvent.SpawnReason.CUSTOM, en -> {
                    mark(en, "minion");
                    if (en instanceof Mob m) {
                        m.setRemoveWhenFarAway(false);
                        m.setTarget(near.get(random.nextInt(near.size())));
                    }
                    if (en instanceof Vex v) v.setLimitedLifetimeTicks(400);
                });
                parts.add(e.getUniqueId());
                l.getWorld().spawnParticle(Particle.LARGE_SMOKE, l, 12, 0.3, 0.3, 0.3, 0.02);
            }
            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 2.5f, 0.6f);
        }

        private void placeTemp(Block b, Material m) {
            if (!b.getType().isAir() || temp.containsKey(b)) return;
            if (plugin.spawn() != null && plugin.spawn().isSpawnWorld(b.getWorld())) return;
            temp.put(b, b.getBlockData());
            b.setType(m, false);
            Bukkit.getScheduler().runTaskLater(plugin, () -> restore(b), 100);
        }

        private void restore(Block b) {
            BlockData old = temp.remove(b);
            if (old != null && b.getType() == Material.COBWEB) b.setBlockData(old, false);
        }

        private void ring(Location c, double r, Color color) {
            for (int i = 0; i < 64; i++) {
                double ang = Math.PI * 2 * i / 64;
                c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(ang) * r, 0.2, Math.sin(ang) * r), 2, 0.1, 0.05, 0.1, new Particle.DustOptions(color, 2f));
            }
        }

        private void line(Location a, Location b, Particle particle) {
            Vector step = b.toVector().subtract(a.toVector());
            double len = step.length();
            step.normalize().multiply(0.5);
            Location at = a.clone();
            for (double s = 0; s < len; s += 0.5) {
                a.getWorld().spawnParticle(particle, at, 2, 0.05, 0.05, 0.05, 0);
                at.add(step);
            }
        }

        /** Raeumt alles auf: Diener, Geschosse, Spinnennetze, Modell. Den Boss selbst entfernt der Aufrufer (oder er ist tot). */
        public void end() {
            if (ended) return;
            ended = true;
            fights.remove(this);
            for (UUID id : parts) {
                Entity e = Bukkit.getEntity(id);
                if (e != null && e.isValid()) e.remove();
            }
            parts.clear();
            for (Block b : new ArrayList<>(temp.keySet())) restore(b);
            plugin.models().detach(boss);
            if (ticket != null) { ticket.removePluginChunkTicket(plugin); ticket = null; }
        }

        boolean owns(Entity e) {
            return e.equals(boss) || parts.contains(e.getUniqueId());
        }
    }

    private static double healthCap() {
        try {
            return Bukkit.spigot().getConfig().getDouble("settings.attribute.maxHealth.max", 1024);
        } catch (Throwable t) {
            return 1024;
        }
    }

    private Fight fightOf(Entity boss) {
        for (Fight f : fights) if (f.boss.equals(boss)) return f;
        return null;
    }

    /** Schaden auf den Boss passend zu seinen (virtuellen) Lebenspunkten verkleinern. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossHurt(EntityDamageEvent e) {
        if (!"boss".equals(mark(e.getEntity()))) return;
        Fight f = fightOf(e.getEntity());
        if (f != null && f.damageScale < 1) e.setDamage(e.getDamage() * f.damageScale);
    }

    private static boolean valid(Player p) {
        return p.isOnline() && !p.isDead() && p.getGameMode() != GameMode.SPECTATOR && p.getGameMode() != GameMode.CREATIVE;
    }

    // ================================================================ Schutz: keine Weltschaeden, keine Beute von Dienern
    @EventHandler(ignoreCancelled = true)
    public void onRock(EntityChangeBlockEvent e) {
        if (!(e.getEntity() instanceof FallingBlock fb) || !"rock".equals(mark(fb))) return;
        e.setCancelled(true);
        Location l = fb.getLocation();
        fb.remove();
        l.getWorld().spawnParticle(Particle.BLOCK, l, 60, 1.2, 0.4, 1.2, Material.COBBLED_DEEPSLATE.createBlockData());
        l.getWorld().spawnParticle(Particle.EXPLOSION, l, 2);
        l.getWorld().playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 2, 0.8f);
        for (Fight f : fights) {
            if (!f.parts.contains(fb.getUniqueId())) continue;
            for (Player p : l.getWorld().getPlayers()) if (valid(p) && p.getLocation().distanceSquared(l) <= 9) p.damage(f.p.damage() * 0.5, f.boss);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (mark(e.getEntity()) != null) e.blockList().clear();
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        String m = mark(e.getEntity());
        if (m == null) return;
        if (m.equals("minion")) { e.getDrops().clear(); e.setDroppedExp(0); }
    }

    /** Diener und Geschosse treffen den Boss nicht; der Boss nimmt keinen Fall- oder Erstickungsschaden. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFriendly(EntityDamageByEntityEvent e) {
        Entity src = e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity sh ? sh : e.getDamager();
        if (mark(e.getEntity()) == null || mark(src) == null || e.getDamager() instanceof Player) return;
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBossDamage(EntityDamageEvent e) {
        if (!"boss".equals(mark(e.getEntity()))) return;
        switch (e.getCause()) {
            case FALL, SUFFOCATION, DROWNING, CRAMMING, FLY_INTO_WALL, FIRE, FIRE_TICK, LAVA, HOT_FLOOR -> e.setCancelled(true);
            default -> { }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (mark(e.getEntity()) != null && e.getTarget() != null && mark(e.getTarget()) != null) e.setCancelled(true);
    }
}
