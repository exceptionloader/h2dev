package de.minesmp.core.events;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Directional;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Schatzkisten in Nether und Farmwelt (Idee wie der Piraten-Token in Fortnite).
 * Kisten erscheinen zufaellig in der Naehe von Spielern (Oberflaeche und Hoehlen), glitzern leicht und zerfallen nach einer Weile.
 * Inhalt: treasure.yml. Sehr selten steckt der Schatz-Token darin: 1 Woche alle Crates gratis (so oft man will) und 1 Woche MVP.
 *
 * Token-Algorithmus (config.yml -> treasure.token), n = Kisten seit dem letzten eigenen Token:
 *   n < min-chests            -> 0 %   (vorher nie)
 *   min-chests <= n < pity     -> base-chance je Kiste
 *   pity <= n < hard-pity      -> steigt quadratisch von base-chance bis pity-max-chance
 *   n >= hard-pity             -> sicher (Pech-Schutz)
 * Serverweit hoechstens max-per-week Tokens in 7 Tagen; ist die Grenze erreicht, gibt es keinen (der Zaehler laeuft weiter).
 * Standard: nie vor Kiste 150, im Schnitt nach ~840 Kisten, spaetestens nach 1500.
 */
public final class TreasureService implements Listener {
    private record Entry(String kind, Object value, int min, int max, int weight, List<String> worlds) {}
    private record Spot(String world, int x, int y, int z) {}

    private static final class Loot implements InventoryHolder {
        Inventory inv;
        @Override public @NotNull Inventory getInventory() { return inv; }
    }

    private final MineSMP plugin;
    private final NamespacedKey chestKey;
    private final Map<Spot, Long> active = new LinkedHashMap<>();   // Kiste -> Ablaufzeit
    private final Map<String, List<Entry>> loot = new LinkedHashMap<>();
    private final Map<String, String> tierNames = new HashMap<>();
    private final Map<String, Double> tierChance = new LinkedHashMap<>();
    private int commonMin = 2, commonMax = 4;

    public TreasureService(MineSMP plugin) {
        this.plugin = plugin;
        this.chestKey = new NamespacedKey(plugin, "treasure_until");
        reload();
        plugin.listen(this);
        plugin.command(new SimpleCommand("schatz", "minesmp.schatz", "Schatzkisten und Schatz-Token", (s, l, a) -> command(s, a),
                (s, a) -> a.length == 1 ? (s.hasPermission("minesmp.admin.schatz") ? List.of("info", "admin") : List.of("info"))
                        : a.length == 2 && a[0].equalsIgnoreCase("admin") && s.hasPermission("minesmp.admin.schatz") ? List.of("spawn", "list", "clear", "token", "reset", "chance")
                        : a.length == 3 && List.of("token", "reset").contains(a[1].toLowerCase(Locale.ROOT)) ? null : List.of(),
                "schatzkiste", "treasure", "token"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::spawnTick, 200, 20L * Math.max(5, cfg().getInt("spawn-interval-seconds", 20)));
        Bukkit.getScheduler().runTaskTimer(plugin, this::sparkle, 100, 40);
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireTick, 1200, 1200);
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("treasure");
        return s != null ? s : plugin.getConfig().createSection("treasure");
    }

    public void reload() {
        YamlConfiguration y = plugin.yaml("treasure.yml");
        loot.clear(); tierNames.clear(); tierChance.clear();
        List<Integer> cr = y.getIntegerList("common-rolls");
        if (cr.size() == 2) { commonMin = cr.get(0); commonMax = Math.max(cr.get(0), cr.get(1)); }
        ConfigurationSection bonus = y.getConfigurationSection("bonus");
        if (bonus != null) for (String t : bonus.getKeys(false)) {
            tierChance.put(t, bonus.getDouble(t + ".chance"));
            tierNames.put(t, bonus.getString(t + ".name", t));
        }
        ConfigurationSection ls = y.getConfigurationSection("loot");
        if (ls != null) for (String tier : ls.getKeys(false)) {
            List<Entry> list = new ArrayList<>();
            for (Map<?, ?> m : ls.getMapList(tier)) {
                int w = m.get("weight") instanceof Number n ? n.intValue() : 1;
                List<String> worlds = m.get("worlds") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
                int[] amt = range(m.get("amount"), 1);
                if (m.containsKey("item")) list.add(new Entry("item", String.valueOf(m.get("item")), amt[0], amt[1], w, worlds));
                else if (m.containsKey("custom")) list.add(new Entry("custom", String.valueOf(m.get("custom")), amt[0], amt[1], w, worlds));
                else if (m.containsKey("money")) { int[] r = range(m.get("money"), 100); list.add(new Entry("money", null, r[0], r[1], w, worlds)); }
                else if (m.containsKey("gems")) { int[] r = range(m.get("gems"), 1); list.add(new Entry("gems", null, r[0], r[1], w, worlds)); }
                else if (m.containsKey("key")) list.add(new Entry("key", String.valueOf(m.get("key")), amt[0], amt[1], w, worlds));
                else if (m.containsKey("book")) list.add(new Entry("book", null, 1, 1, w, worlds));
                else if (m.containsKey("drachen")) list.add(new Entry("drachen", null, 1, 1, w, worlds));
            }
            loot.put(tier, list);
        }
    }

    private static int[] range(Object o, int def) {
        if (o instanceof List<?> l && l.size() == 2 && l.get(0) instanceof Number a && l.get(1) instanceof Number b) return new int[]{a.intValue(), Math.max(a.intValue(), b.intValue())};
        if (o instanceof Number n) return new int[]{n.intValue(), n.intValue()};
        return new int[]{def, def};
    }

    // ================================================================ Token-Algorithmus

    /** Chance, dass die n-te Kiste seit dem letzten Token den Token enthaelt. */
    public double tokenChance(long n) {
        ConfigurationSection t = cfg().getConfigurationSection("token");
        int min = t == null ? 150 : t.getInt("min-chests", 150);
        double base = t == null ? 0.0006 : t.getDouble("base-chance", 0.0006);
        int pity = t == null ? 600 : t.getInt("pity-start", 600);
        double pmax = t == null ? 0.02 : t.getDouble("pity-max-chance", 0.02);
        int hard = t == null ? 1500 : t.getInt("hard-pity", 1500);
        if (n < min) return 0;
        if (hard > 0 && n >= hard) return 1;
        if (n < pity || hard <= pity) return base;
        double x = (double) (n - pity) / (hard - pity);
        return base + (pmax - base) * x * x;
    }

    private List<Long> tokenLog() {
        List<Long> out = new ArrayList<>();
        try {
            String v = plugin.db().kvGet("treasure-tokens").get(3, java.util.concurrent.TimeUnit.SECONDS);
            if (v != null) for (String s : v.split(",")) if (!s.isBlank()) out.add(Long.parseLong(s.trim()));
        } catch (Exception ignored) { }
        long weekAgo = System.currentTimeMillis() - Duration.ofDays(7).toMillis();
        out.removeIf(t -> t < weekAgo);
        return out;
    }

    private boolean tokenAvailable() {
        int max = cfg().getInt("token.max-per-week", 2);
        return max <= 0 || tokenLog().size() < max;
    }

    public long freeCratesUntil(Player p) {
        PlayerData d = plugin.data().getIfLoaded(p.getUniqueId());
        return d == null ? 0 : d.stat("treasure_token_until");
    }

    /** Schatz-Token aktiv: alle Crates gratis. */
    public boolean freeCrates(Player p) {
        return freeCratesUntil(p) > System.currentTimeMillis();
    }

    /** Vergibt den Token (Fund oder Admin): Zeitraum verlaengern, Rang, Ansage, Feuerwerk. */
    public void grantToken(Player p, String reason) {
        PlayerData d = plugin.data().get(p);
        int days = Math.max(1, cfg().getInt("token.days", 7));
        long now = System.currentTimeMillis();
        long until = Math.max(now, d.stat("treasure_token_until")) + Duration.ofDays(days).toMillis();
        d.stats.put("treasure_token_until", until);
        d.stats.put("treasure_pity", 0L);
        d.addStat("treasure_tokens", 1);
        d.dirty = true;
        List<Long> log = tokenLog();
        log.add(now);
        plugin.db().kvSet("treasure-tokens", String.join(",", log.stream().map(String::valueOf).toList()));
        String cmd = cfg().getString("token.rank-command", "lp user {player} parent addtemp {rank} {days}d accumulate");
        if (!cmd.isBlank() && Bukkit.getPluginManager().isPluginEnabled("LuckPerms"))
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()).replace("{rank}", cfg().getString("token.rank", "mvp")).replace("{days}", String.valueOf(days)));
        ItemStack trophy = plugin.customItems().create("schatztoken");
        if (trophy != null) {
            trophy.editMeta(m -> {
                List<net.kyori.adventure.text.Component> lore = m.hasLore() ? new ArrayList<>(m.lore()) : new ArrayList<>();
                lore.add(Text.item("&8Gefunden von " + p.getName() + " am " + java.time.LocalDate.now(java.time.ZoneId.of("Europe/Berlin"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))));
                m.lore(lore);
            });
            Items.giveOrDrop(p, trophy);
        }
        plugin.audit().log(p.getName(), "TREASURE_TOKEN", reason + " bis " + until);
        Msg.broadcast("treasure.token-broadcast", "{prefix}&6&l✦ SCHATZ-TOKEN! &f{player} &7hat den Schatz-Token gefunden! &e{days} Tage &7alle Crates gratis und &3&lMVP&7.",
                "player", p.getName(), "days", String.valueOf(days));
        Title t = Title.title(Text.c("&6&lSCHATZ-TOKEN!"), Text.c("&f" + p.getName() + " &7hat ihn gefunden"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800)));
        for (Player o : Bukkit.getOnlinePlayers()) {
            o.showTitle(t);
            o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        }
        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.6f);
        Firework fw = p.getWorld().spawn(p.getLocation().add(0, 1, 0), Firework.class, f -> {
            FireworkMeta fm = f.getFireworkMeta();
            fm.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.STAR).withColor(Color.fromRGB(0xFFD86B), Color.fromRGB(0xFF9E2C))
                    .withFade(Color.WHITE).flicker(true).trail(true).build());
            fm.setPower(1);
            f.setFireworkMeta(fm);
        });
        fw.getPersistentDataContainer().set(new NamespacedKey(plugin, "no_damage"), PersistentDataType.BYTE, (byte) 1);
        Msg.send(p, "treasure.token-self", "{prefix}&aDein Schatz-Token gilt noch &f{until}&a: &f/keys &7oder die Kisten am Spawn - Schluessel brauchst du keine.",
                "until", TimeUtil.format(until - now));
    }

    // ================================================================ Kisten setzen

    private List<String> worldNames() {
        ConfigurationSection ws = cfg().getConfigurationSection("worlds");
        if (ws == null) return List.of(plugin.getConfig().getString("worlds.farmworld.name", "farmwelt"), "world_nether");
        return new ArrayList<>(ws.getKeys(false));
    }

    private void spawnTick() {
        if (!cfg().getBoolean("enabled", true)) return;
        for (String wn : worldNames()) {
            World w = Bukkit.getWorld(wn);
            if (w == null) continue;
            List<Player> players = w.getPlayers().stream().filter(p -> p.getGameMode() != GameMode.SPECTATOR).toList();
            if (players.isEmpty()) continue;
            int per = cfg().getInt("worlds." + wn + ".per-player", 6), max = cfg().getInt("worlds." + wn + ".max", 50);
            int target = Math.min(max, per * players.size());
            long have = active.keySet().stream().filter(s -> s.world.equals(wn)).count();
            for (int i = 0; i < 3 && have < target; i++) {
                Player near = players.get(ThreadLocalRandom.current().nextInt(players.size()));
                if (place(w, near.getLocation()) != null) have++;
            }
        }
    }

    /** Sucht einen Platz in der Naehe (nur geladene Chunks) und setzt eine Schatzkiste. */
    public Block place(World w, Location around) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int minD = cfg().getInt("min-distance", 24), maxD = Math.max(minD + 1, cfg().getInt("max-distance", 110));
        for (int attempt = 0; attempt < 12; attempt++) {
            double ang = r.nextDouble(Math.PI * 2), dist = minD + r.nextDouble(maxD - minD);
            int x = around.getBlockX() + (int) Math.round(Math.cos(ang) * dist), z = around.getBlockZ() + (int) Math.round(Math.sin(ang) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (!w.getWorldBorder().isInside(new Location(w, x + 0.5, around.getY(), z + 0.5))) continue;
            Block b = w.getEnvironment() == World.Environment.NETHER ? netherSpot(w, x, z) : overworldSpot(w, x, z, r.nextDouble() < cfg().getDouble("underground-chance", 0.45));
            if (b == null || tooClose(b) || (plugin.claims() != null && plugin.claims().at(b) != null)) continue;
            b.setType(Material.CHEST, false);
            if (b.getBlockData() instanceof Directional dir) {
                dir.setFacing(List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST).get(r.nextInt(4)));
                b.setBlockData(dir, false);
            }
            long until = System.currentTimeMillis() + Duration.ofMinutes(Math.max(1, cfg().getInt("lifetime-minutes", 45))).toMillis();
            if (b.getState(false) instanceof Chest c) {
                c.getPersistentDataContainer().set(chestKey, PersistentDataType.LONG, until);
                c.update(true, false);
            }
            active.put(new Spot(w.getName(), b.getX(), b.getY(), b.getZ()), until);
            return b;
        }
        return null;
    }

    private boolean tooClose(Block b) {
        for (Spot s : active.keySet())
            if (s.world.equals(b.getWorld().getName()) && Math.abs(s.x - b.getX()) < 12 && Math.abs(s.z - b.getZ()) < 12 && Math.abs(s.y - b.getY()) < 12) return true;
        return false;
    }

    private static boolean floor(Block below) {
        Material m = below.getType();
        return m.isSolid() && m.isOccluding() && m != Material.MAGMA_BLOCK && m != Material.BEDROCK && !m.name().endsWith("_LEAVES");
    }

    private static boolean free(Block b) {
        if (!b.getType().isAir()) return false;
        for (BlockFace f : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP)) {
            Material m = b.getRelative(f).getType();
            if (m == Material.LAVA || m == Material.WATER) return false;
        }
        return b.getRelative(BlockFace.UP).getType().isAir() && floor(b.getRelative(BlockFace.DOWN));
    }

    private Block overworldSpot(World w, int x, int z, boolean underground) {
        Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (!underground) {
            Block b = top.getRelative(BlockFace.UP);
            return free(b) ? b : null;
        }
        List<Block> caves = new ArrayList<>();
        for (int y = top.getY() - 8; y > w.getMinHeight() + 4; y--) {
            Block b = w.getBlockAt(x, y, z);
            if (free(b)) caves.add(b);
        }
        return caves.isEmpty() ? null : caves.get(ThreadLocalRandom.current().nextInt(caves.size()));
    }

    private Block netherSpot(World w, int x, int z) {
        List<Block> spots = new ArrayList<>();
        for (int y = 118; y > 8; y--) {
            Block b = w.getBlockAt(x, y, z);
            if (free(b)) spots.add(b);
        }
        return spots.isEmpty() ? null : spots.get(ThreadLocalRandom.current().nextInt(spots.size()));
    }

    private Long treasureUntil(Block b) {
        if (b == null || b.getType() != Material.CHEST || !(b.getState(false) instanceof Chest c)) return null;
        return c.getPersistentDataContainer().get(chestKey, PersistentDataType.LONG);
    }

    private void sparkle() {
        if (!cfg().getBoolean("particles", true)) return;
        for (Spot s : active.keySet()) {
            World w = Bukkit.getWorld(s.world);
            if (w == null || !w.isChunkLoaded(s.x >> 4, s.z >> 4)) continue;
            Location l = new Location(w, s.x + 0.5, s.y + 0.9, s.z + 0.5);
            boolean near = false;
            for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(l) < 24 * 24) { near = true; break; }
            if (near) w.spawnParticle(Particle.WAX_ON, l, 5, 0.35, 0.3, 0.35, 0.02);
        }
    }

    private void expireTick() {
        long now = System.currentTimeMillis();
        active.entrySet().removeIf(en -> {
            if (en.getValue() > now) return false;
            Spot s = en.getKey();
            World w = Bukkit.getWorld(s.world);
            if (w != null && w.isChunkLoaded(s.x >> 4, s.z >> 4)) {
                Block b = w.getBlockAt(s.x, s.y, s.z);
                if (treasureUntil(b) != null) vanish(b);
            }
            return true;   // nicht geladene Kisten raeumt onChunkLoad weg
        });
    }

    private void vanish(Block b) {
        if (b.getState(false) instanceof Chest c) c.getBlockInventory().clear();
        b.setType(Material.AIR, false);
        b.getWorld().spawnParticle(Particle.CLOUD, b.getLocation().add(0.5, 0.5, 0.5), 8, 0.2, 0.2, 0.2, 0.02);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        long now = System.currentTimeMillis();
        for (BlockState st : e.getChunk().getTileEntities()) {
            if (!(st instanceof Chest c)) continue;
            Long until = c.getPersistentDataContainer().get(chestKey, PersistentDataType.LONG);
            if (until == null) continue;
            Spot s = new Spot(c.getWorld().getName(), c.getX(), c.getY(), c.getZ());
            if (until <= now) Bukkit.getScheduler().runTask(plugin, () -> { if (treasureUntil(c.getBlock()) != null) vanish(c.getBlock()); });
            else active.putIfAbsent(s, until);
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent e) {
        active.keySet().removeIf(s -> s.world.equals(e.getWorld().getName()));
    }

    // ================================================================ Oeffnen

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        Long until = treasureUntil(b);
        if (until == null) return;
        e.setCancelled(true);
        open(e.getPlayer(), b, until);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Long until = treasureUntil(e.getBlock());
        if (until == null) return;
        e.setCancelled(true);
        open(e.getPlayer(), e.getBlock(), until);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFireworkDamage(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Firework f && f.getPersistentDataContainer().has(new NamespacedKey(plugin, "no_damage"))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> treasureUntil(b) != null); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> treasureUntil(b) != null); }

    private void open(Player p, Block b, long until) {
        active.remove(new Spot(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        vanish(b);
        if (until <= System.currentTimeMillis()) {
            Msg.send(p, "treasure.decayed", "{prefix}&7Diese Schatzkiste ist schon zerfallen.");
            return;
        }
        PlayerData d = plugin.data().get(p);
        d.addStat("treasure_opened", 1);
        d.addStat("treasure_pity", 1);
        d.dirty = true;
        long n = d.stat("treasure_pity");
        boolean token = ThreadLocalRandom.current().nextDouble() < tokenChance(n) && tokenAvailable();

        String place = b.getWorld().getEnvironment() == World.Environment.NETHER ? "nether" : "farmwelt";
        List<ItemStack> items = new ArrayList<>();
        List<String> credited = new ArrayList<>();
        int rolls = ThreadLocalRandom.current().nextInt(commonMin, commonMax + 1);
        String common = loot.containsKey("gewoehnlich") ? "gewoehnlich" : loot.keySet().stream().findFirst().orElse(null);
        for (int i = 0; i < rolls && common != null; i++) give(p, pick(common, place), items, credited);
        String bonus = null;
        double r = ThreadLocalRandom.current().nextDouble(), acc = 0;
        List<String> order = new ArrayList<>(tierChance.keySet());
        java.util.Collections.reverse(order);   // seltenste Stufe zuerst
        for (String t : order) { acc += tierChance.get(t); if (r < acc) { bonus = t; break; } }
        if (bonus != null) give(p, pick(bonus, place), items, credited);

        String label = bonus == null ? "" : " &8(" + tierNames.getOrDefault(bonus, bonus) + "&8)";
        Loot holder = new Loot();
        Inventory inv = Bukkit.createInventory(holder, 27, Text.c("&8Schatzkiste" + label));
        holder.inv = inv;
        int[] slots = {13, 12, 14, 11, 15, 4, 22, 10, 16, 3, 5, 21, 23};
        int k = 0;
        for (ItemStack it : items) {
            if (k < slots.length) inv.setItem(slots[k++], it);
            else inv.addItem(it).values().forEach(left -> Items.giveOrDrop(p, left));
        }
        p.openInventory(inv);
        p.playSound(at, Sound.BLOCK_CHEST_OPEN, 1f, 1.1f);
        p.playSound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.8f);
        at.getWorld().spawnParticle(Particle.WAX_ON, at, 20, 0.4, 0.4, 0.4, 0.05);
        if (!credited.isEmpty()) Msg.send(p, "treasure.credited", "{prefix}&7Gutgeschrieben: {list}", "list", String.join("&7, ", credited));
        if (bonus != null && "legendaer".equals(bonus))
            Msg.broadcast("treasure.legendary", "{prefix}&6&l✦ &f{player} &7hat eine &6&llegendaere &7Schatzkiste gefunden!", "player", p.getName());
        if (token) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) grantToken(p, "Schatzkiste Nr. " + n); }, 15L);
    }

    private Entry pick(String tier, String place) {
        List<Entry> list = loot.getOrDefault(tier, List.of()).stream().filter(e -> e.worlds.isEmpty() || e.worlds.contains(place)).toList();
        int total = list.stream().mapToInt(Entry::weight).sum();
        if (total <= 0) return null;
        int r = ThreadLocalRandom.current().nextInt(total);
        for (Entry e : list) { r -= e.weight; if (r < 0) return e; }
        return null;
    }

    private void give(Player p, Entry e, List<ItemStack> items, List<String> credited) {
        if (e == null) return;
        int n = e.min >= e.max ? e.min : ThreadLocalRandom.current().nextInt(e.min, e.max + 1);
        switch (e.kind) {
            case "item" -> {
                Material m = Material.matchMaterial(String.valueOf(e.value));
                if (m != null && m.isItem()) items.add(new ItemStack(m, Math.min(n, m.getMaxStackSize())));
            }
            case "custom" -> {
                ItemStack it = plugin.customItems().create(String.valueOf(e.value));
                if (it != null) { it.setAmount(Math.min(n, it.getMaxStackSize())); items.add(it); }
            }
            case "key" -> {
                ItemStack it = plugin.crates() == null ? null : plugin.crates().keyItem(String.valueOf(e.value), n);
                if (it != null) items.add(it);
            }
            case "money" -> { plugin.eco().deposit(p, n, "TREASURE", "Schatzkiste"); credited.add("&a" + plugin.eco().format(n)); }
            case "gems" -> {
                if (plugin.tokens() != null) {
                    plugin.tokens().give(p.getUniqueId(), de.minesmp.core.economy.TokenService.Kind.GEMS, n, "Schatzkiste");
                    credited.add("&d" + n + " Gems");
                }
            }
            case "book" -> items.add(randomBook());
            case "drachen" -> {
                ItemStack it = plugin.dragonGear() == null ? null : plugin.dragonGear().randomGear();
                if (it != null) items.add(it);
            }
            default -> { }
        }
    }

    private static ItemStack randomBook() {
        List<Enchantment> all = Registry.ENCHANTMENT.stream().filter(en -> !en.isCursed()).toList();
        Enchantment en = all.get(ThreadLocalRandom.current().nextInt(all.size()));
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        book.editMeta(EnchantmentStorageMeta.class, m -> m.addStoredEnchant(en, ThreadLocalRandom.current().nextInt(1, en.getMaxLevel() + 1), false));
        return book;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Loot) || !(e.getPlayer() instanceof Player p)) return;
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack it : e.getInventory().getContents()) if (it != null && !it.getType().isAir()) left.add(it);
        e.getInventory().clear();
        if (!left.isEmpty()) Items.giveOrDrop(p, left.toArray(new ItemStack[0]));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = e.getPlayer();
            if (p.isOnline() && freeCrates(p))
                Msg.send(p, "treasure.token-active", "{prefix}&6Schatz-Token aktiv: &falle Crates gratis &7noch &f{left}&7.", "left", TimeUtil.format(freeCratesUntil(p) - System.currentTimeMillis()));
        }, 60L);
    }

    // ================================================================ Befehl

    private void command(CommandSender s, String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("admin") && s.hasPermission("minesmp.admin.schatz")) { admin(s, a); return; }
        if (!(s instanceof Player p)) { s.sendMessage("/schatz admin ..."); return; }
        PlayerData d = plugin.data().get(p);
        long here = active.keySet().stream().filter(x -> x.world.equals(p.getWorld().getName())).count();
        String tokenLine = freeCrates(p) ? "&6Schatz-Token aktiv: &falle Crates gratis &7noch &f" + TimeUtil.format(freeCratesUntil(p) - System.currentTimeMillis())
                : "&7Schatz-Token: &fnoch nicht gefunden &8(sehr selten!)";
        Msg.send(p, "treasure.info", "{prefix}&6&lSchatzkisten\n&8» &7Sie erscheinen im &cNether &7und in der &aFarmwelt &7- an der Oberflaeche und in Hoehlen, mit leichtem Glitzern.\n&8» &7Geoeffnet: &f{opened} &8| &7seit dem letzten Token: &f{since} &8| &7Tokens: &f{tokens}\n&8» {token}\n&8» &7In deiner Welt gerade: &f{here} Kisten\n&8» &7Der Token ist extrem selten: &f1 Woche alle Crates gratis &7+ &3&lMVP&7.",
                "opened", String.valueOf(d.stat("treasure_opened")), "since", String.valueOf(d.stat("treasure_pity")),
                "tokens", String.valueOf(d.stat("treasure_tokens")), "token", tokenLine, "here", String.valueOf(here));
    }

    private void admin(CommandSender s, String[] a) {
        String sub = a.length < 2 ? "" : a[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "spawn" -> {
                if (!(s instanceof Player p)) return;
                int n = a.length > 2 ? Math.max(1, Math.min(20, parse(a[2], 1))) : 1;
                int ok = 0;
                for (int i = 0; i < n; i++) if (place(p.getWorld(), p.getLocation()) != null) ok++;
                s.sendMessage(Text.c("&a" + ok + " Schatzkiste(n) gesetzt."));
            }
            case "list" -> {
                s.sendMessage(Text.c("&6" + active.size() + " aktive Schatzkisten:"));
                active.forEach((sp, t) -> s.sendMessage(Text.c("&7" + sp.world + " " + sp.x + " " + sp.y + " " + sp.z + " &8(noch " + TimeUtil.format(t - System.currentTimeMillis()) + ")")));
            }
            case "clear" -> {
                int n = 0;
                for (Spot sp : new ArrayList<>(active.keySet())) {
                    World w = Bukkit.getWorld(sp.world);
                    if (w != null && w.isChunkLoaded(sp.x >> 4, sp.z >> 4)) { Block b = w.getBlockAt(sp.x, sp.y, sp.z); if (treasureUntil(b) != null) { vanish(b); n++; } }
                }
                active.clear();
                s.sendMessage(Text.c("&a" + n + " Schatzkisten entfernt."));
            }
            case "token" -> {
                Player t = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : null;
                if (t == null) { s.sendMessage("/schatz admin token <Spieler>"); return; }
                grantToken(t, "Admin " + s.getName());
            }
            case "reset" -> {
                Player t = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : null;
                if (t == null) { s.sendMessage("/schatz admin reset <Spieler>"); return; }
                PlayerData d = plugin.data().get(t);
                d.stats.remove("treasure_token_until");
                d.stats.put("treasure_pity", 0L);
                d.dirty = true;
                s.sendMessage(Text.c("&aSchatz-Token und Zaehler von " + t.getName() + " zurueckgesetzt (Rang bleibt bis Ablauf)."));
            }
            case "chance" -> {
                // Wahrscheinlichkeit, den Token bis Kiste n gefunden zu haben (mit den aktuellen Einstellungen)
                double surv = 1, exp = 0;
                Map<Integer, Double> at = new LinkedHashMap<>();
                List<Integer> marks = List.of(50, 100, 150, 300, 500, 800, 1000, 1200, 1500, 2000);
                for (int n = 1; n <= 5000 && surv > 1e-9; n++) {
                    double q = tokenChance(n);
                    exp += n * surv * q;
                    surv *= 1 - q;
                    if (marks.contains(n)) at.put(n, 1 - surv);
                }
                s.sendMessage(Text.c("&6Schatz-Token: im Schnitt nach &f" + Math.round(exp) + " &6Kisten. Chance bis Kiste ..."));
                at.forEach((n, v) -> s.sendMessage(Text.c("&7  " + n + ": &f" + String.format(Locale.GERMANY, "%.1f", v * 100) + " %")));
                s.sendMessage(Text.c("&7Serverweit diese Woche vergeben: &f" + tokenLog().size() + "&7/&f" + cfg().getInt("token.max-per-week", 2)));
            }
            default -> s.sendMessage(Text.c("&7/schatz admin spawn [n] | list | clear | token <Spieler> | reset <Spieler> | chance"));
        }
    }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    public int activeCount() { return active.size(); }
}
