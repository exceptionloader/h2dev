package de.minesmp.core.progress;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Skills (Mining, Farming, Woodcutting, Fishing, Combat) + Spielerlevel.
 * Geld fuer Aktionen wird gesammelt und alle 30 Sekunden gutgeschrieben (weniger Log-Eintraege).
 * Von Spielern platzierte Erze/Staemme geben keine Belohnung (Markierung im Chunk-PDC).
 */
public final class SkillService implements Listener {
    public record Skill(String id, String name, Material icon, Map<String, Double> xp, Map<String, Double> money) {}

    private final MineSMP plugin;
    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final Map<Material, String> blockSkill = new EnumMap<>(Material.class);
    private final Map<UUID, Double> pendingMoney = new HashMap<>();
    private final Map<UUID, Double> pendingXpShown = new HashMap<>();
    private final NamespacedKey placedKey;
    private YamlConfiguration cfg;

    public SkillService(MineSMP plugin) {
        this.plugin = plugin;
        this.placedKey = new NamespacedKey(plugin, "placed");
        load();
        plugin.listen(this);
        plugin.command(new SimpleCommand("skills", "minesmp.skills", "Skills & Level", (s, l, a) -> { if (s instanceof Player p) open(p); }, "level", "skill"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 600, 600);
    }

    private void load() {
        cfg = plugin.yaml("skills.yml");
        ConfigurationSection root = cfg.getConfigurationSection("skills");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            Map<String, Double> xp = new HashMap<>(), money = new HashMap<>();
            ConfigurationSection xs = s.getConfigurationSection("xp");
            if (xs != null) for (String k : xs.getKeys(false)) xp.put(k.toUpperCase(Locale.ROOT), xs.getDouble(k));
            ConfigurationSection ms = s.getConfigurationSection("money");
            if (ms != null) for (String k : ms.getKeys(false)) money.put(k.toUpperCase(Locale.ROOT), ms.getDouble(k));
            Material icon = Material.matchMaterial(s.getString("icon", "BOOK"));
            Skill sk = new Skill(id, s.getString("name", id), icon == null ? Material.BOOK : icon, xp, money);
            skills.put(id, sk);
            if (id.equals("mining") || id.equals("woodcutting") || id.equals("farming")) {
                for (String k : xp.keySet()) {
                    Material m = Material.matchMaterial(k);
                    if (m != null) blockSkill.put(m, id);
                }
            }
        }
    }

    public Map<String, Skill> skills() { return skills; }

    // ---------------- Level-Formeln ----------------

    public double xpForSkillLevel(int level) {
        return cfg.getDouble("formula.skill-base", 100) * Math.pow(level, cfg.getDouble("formula.skill-exponent", 1.6));
    }

    public int skillLevel(PlayerData d, String skill) {
        double xp = d.skill(skill);
        int lvl = 1, max = cfg.getInt("formula.max-skill-level", 100);
        while (lvl < max && xp >= xpForSkillLevel(lvl)) { xp -= xpForSkillLevel(lvl); lvl++; }
        return lvl;
    }

    /** XP im aktuellen Level und benoetigte XP fuer das naechste. */
    public double[] skillProgress(PlayerData d, String skill) {
        double xp = d.skill(skill);
        int lvl = 1, max = cfg.getInt("formula.max-skill-level", 100);
        while (lvl < max && xp >= xpForSkillLevel(lvl)) { xp -= xpForSkillLevel(lvl); lvl++; }
        return new double[]{xp, xpForSkillLevel(lvl)};
    }

    public double xpForPlayerLevel(int level) {
        return cfg.getDouble("formula.player-base", 250) * Math.pow(level, cfg.getDouble("formula.player-exponent", 1.5));
    }

    /** Fuegt Skill-XP und Spieler-XP hinzu und prueft Level-Ups. */
    public void addXp(Player p, String skill, double amount) {
        if (amount <= 0) return;
        PlayerData d = plugin.data().get(p);
        double mult = p.hasPermission("minesmp.xp.boost") ? cfg.getDouble("vip-xp-multiplier", 1.25) : 1.0;
        amount *= mult;
        int before = skillLevel(d, skill);
        d.skillXp.merge(skill, amount, Double::sum);
        int after = skillLevel(d, skill);
        if (after > before) {
            Skill sk = skills.get(skill);
            double reward = cfg.getDouble("skill-levelup-money-per-level", 100) * after;
            plugin.eco().deposit(p, reward, "SKILL_LEVEL", skill + " " + after);
            Msg.send(p, "skill.levelup", "{prefix}&b&lLEVEL UP! &f{skill} &7ist jetzt Level &b{level}&7! &a+{money}",
                    "skill", sk == null ? skill : sk.name(), "level", String.valueOf(after), "money", plugin.eco().format(reward));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.4f);
        }
        d.xp += amount;
        while (d.xp >= xpForPlayerLevel(d.level) && d.level < cfg.getInt("formula.max-player-level", 200)) {
            d.xp -= xpForPlayerLevel(d.level);
            d.level++;
            Msg.broadcast("player.levelup", "{prefix}&f{name} &7hat &eSpielerlevel {level} &7erreicht!", "name", p.getName(), "level", String.valueOf(d.level));
            giveLevelRewards(p, d);
        }
        d.dirty = true;
        pendingXpShown.merge(p.getUniqueId(), amount, Double::sum);
        plugin.battlepass().addXp(p, (int) Math.max(1, Math.round(amount / 10)));
    }

    private void giveLevelRewards(Player p, PlayerData d) {
        ConfigurationSection r = cfg.getConfigurationSection("level-rewards." + d.level);
        if (r == null || d.levelRewards.contains(d.level)) return;
        d.levelRewards.add(d.level);
        plugin.rewards().give(p, r, "Level " + d.level);
    }

    private void earn(Player p, double money) {
        if (money <= 0) return;
        pendingMoney.merge(p.getUniqueId(), money * plugin.shop().multiplier(p) * plugin.events().moneyMultiplier(), Double::sum);
    }

    private void flush() {
        for (var e : new ArrayList<>(pendingMoney.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && e.getValue() >= 0.01) {
                plugin.eco().deposit(p, e.getValue(), "SKILL", "Aktivitaet");
                Msg.markActionbar(p, 3500);
                p.sendActionBar(Text.c(Msg.get("skill.earned", "&a+{money} &8| &b+{xp} XP &7(letzte 30s)",
                        "money", plugin.eco().format(e.getValue()), "xp", Text.money(pendingXpShown.getOrDefault(e.getKey(), 0.0)))));
            }
        }
        pendingMoney.clear();
        pendingXpShown.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Double m = pendingMoney.remove(e.getPlayer().getUniqueId());
        pendingXpShown.remove(e.getPlayer().getUniqueId());
        if (m != null && m >= 0.01) plugin.eco().deposit(e.getPlayer(), m, "SKILL", "Aktivitaet");
    }

    // ---------------- Platzierte Bloecke markieren ----------------

    private static int packed(Block b) {
        return ((b.getX() & 15) << 28) | ((b.getZ() & 15) << 24) | ((b.getY() + 2048) & 0xFFFFFF);
    }

    private boolean isPlaced(Block b) {
        int[] arr = b.getChunk().getPersistentDataContainer().get(placedKey, PersistentDataType.INTEGER_ARRAY);
        if (arr == null) return false;
        int k = packed(b);
        for (int v : arr) if (v == k) return true;
        return false;
    }

    private void setPlaced(Block b, boolean placed) {
        Chunk c = b.getChunk();
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        int[] arr = pdc.getOrDefault(placedKey, PersistentDataType.INTEGER_ARRAY, new int[0]);
        int k = packed(b);
        List<Integer> list = new ArrayList<>(arr.length + 1);
        for (int v : arr) if (v != k) list.add(v);
        if (placed) list.add(k);
        if (list.isEmpty()) pdc.remove(placedKey);
        else pdc.set(placedKey, PersistentDataType.INTEGER_ARRAY, list.stream().mapToInt(Integer::intValue).toArray());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        String sk = blockSkill.get(e.getBlock().getType());
        if (sk != null && !sk.equals("farming")) setPlaced(e.getBlock(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) { movePlaced(e.getBlocks(), e.getDirection()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) { movePlaced(e.getBlocks(), e.getDirection()); }

    private void movePlaced(List<Block> blocks, org.bukkit.block.BlockFace dir) {
        List<Block> moved = new ArrayList<>();
        for (Block b : blocks) if (blockSkill.containsKey(b.getType()) && isPlaced(b)) moved.add(b);
        for (Block b : moved) setPlaced(b, false);
        for (Block b : moved) setPlaced(b.getRelative(dir), true);
    }

    // ---------------- Aktionen ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() != org.bukkit.GameMode.SURVIVAL) return;
        Block b = e.getBlock();
        Material m = b.getType();
        String skill = blockSkill.get(m);
        plugin.quests().progress(p, "BREAK", m.name(), 1);
        plugin.data().get(p).addStat("blocks_broken", 1);
        if (skill == null) return;
        if (skill.equals("farming")) {
            if (b.getBlockData() instanceof Ageable age && age.getAge() < age.getMaximumAge()) return;
            plugin.quests().progress(p, "HARVEST", m.name(), 1);
            plugin.events().onAction(p, "FARMING", 1);
        } else if (isPlaced(b)) {
            setPlaced(b, false);
            return;
        }
        Skill sk = skills.get(skill);
        addXp(p, skill, sk.xp.getOrDefault(m.name(), 0.0));
        earn(p, sk.money.getOrDefault(m.name(), 0.0));
        if (skill.equals("mining")) plugin.events().onAction(p, "MINING", 1);
        if (skill.equals("woodcutting")) plugin.events().onAction(p, "WOODCUTTING", 1);
        plugin.events().onAction(p, "COMMUNITY_" + skill.toUpperCase(Locale.ROOT), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Player p = e.getPlayer();
        Skill sk = skills.get("fishing");
        if (sk == null) return;
        String key = e.getCaught() instanceof org.bukkit.entity.Item it ? it.getItemStack().getType().name() : "DEFAULT";
        addXp(p, "fishing", sk.xp.getOrDefault(key, sk.xp.getOrDefault("DEFAULT", 10.0)));
        earn(p, sk.money.getOrDefault(key, sk.money.getOrDefault("DEFAULT", 5.0)));
        plugin.quests().progress(p, "FISH", key, 1);
        plugin.events().onAction(p, "FISHING", 1);
        plugin.data().get(p).addStat("fish_caught", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        LivingEntity ent = e.getEntity();
        if (ent instanceof Player) return;
        Player p = ent.getKiller();
        if (p == null) return;
        if (fromSpawner(ent) && cfg.getBoolean("spawner-mobs-give-no-rewards", true)) return;
        Skill sk = skills.get("combat");
        if (sk == null) return;
        String type = ent.getType().name();
        addXp(p, "combat", sk.xp.getOrDefault(type, sk.xp.getOrDefault("DEFAULT", 3.0)));
        earn(p, sk.money.getOrDefault(type, sk.money.getOrDefault("DEFAULT", 1.0)));
        plugin.quests().progress(p, "KILL_MOB", type, 1);
        plugin.data().get(p).addStat("mobs_killed", 1);
        plugin.events().onMobKill(p, ent);
    }

    private static boolean fromSpawner(Entity e) {
        return e.getEntitySpawnReason() == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER;
    }

    // ---------------- GUI ----------------

    public void open(Player p) {
        PlayerData d = plugin.data().get(p);
        Menu m = new Menu(3, "&8Deine Skills") {};
        m.set(4, Items.of(Material.EXPERIENCE_BOTTLE, 1, "&e&lSpielerlevel " + d.level, List.of(
                "&7XP: &f" + Text.money(d.xp) + "&7/&f" + Text.money(xpForPlayerLevel(d.level)),
                Text.progressBar(d.xp / xpForPlayerLevel(d.level), 20, "&e", "&8"))));
        int slot = 11;
        for (Skill sk : skills.values()) {
            if (slot > 15) break;
            double[] prog = skillProgress(d, sk.id);
            m.set(slot++, Items.of(sk.icon, 1, sk.name + " &7- Level &b" + skillLevel(d, sk.id), List.of(
                    "&7XP: &f" + Text.money(prog[0]) + "&7/&f" + Text.money(prog[1]),
                    Text.progressBar(prog[0] / prog[1], 20, "&b", "&8"))));
        }
        m.set(22, Items.of(Material.BOOK, 1, "&bQuests & Battlepass", List.of("&7/quests &8| &7/battlepass")));
        m.fillEmpty();
        m.open(p);
    }
}
