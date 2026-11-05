package de.minesmp.core.crate;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Crates (Vote, Daily, Rare, Epic, Legendary). Schluessel virtuell (/keys) oder als Item.
 * Gewinn wird beim Start fair per Gewichtung bestimmt; Animation ist nur Optik.
 * Wird das Menue waehrend der Animation geschlossen, wird der Gewinn sofort vergeben.
 */
public final class CrateService implements Listener {
    public record Reward(String id, double chance, ItemStack display, ConfigurationSection reward, boolean broadcast) {}
    public record Crate(String id, String name, Material keyMaterial, List<Reward> rewards, double totalWeight) {}

    private final MineSMP plugin;
    private final Map<String, Crate> crates = new LinkedHashMap<>();
    private final Map<String, String> blockToCrate = new LinkedHashMap<>();
    private final NamespacedKey keyTag, holoTag;
    private YamlConfiguration cfg;

    public CrateService(MineSMP plugin) {
        this.plugin = plugin;
        this.keyTag = new NamespacedKey(plugin, "crate_key");
        this.holoTag = new NamespacedKey(plugin, "crate_holo");
        reload();
        plugin.listen(this);
        plugin.command(new SimpleCommand("crate", "minesmp.crate", "Kisten", this::cmd, (s, a) -> {
            if (a.length == 1) return s.hasPermission("minesmp.admin.crate") ? List.of("open", "preview", "givekey", "setblock", "removeblock") : List.of("open", "preview");
            if (a.length == 2 && a[0].equalsIgnoreCase("givekey")) return null;
            if ((a.length == 2 && !a[0].equalsIgnoreCase("givekey")) || a.length == 3) return new ArrayList<>(crates.keySet());
            return List.of();
        }, "crates", "kisten"));
        plugin.command(new SimpleCommand("keys", "minesmp.crate", "Deine Schluessel", (s, l, a) -> { if (s instanceof Player p) openOverview(p); }, "schluessel"));
        Bukkit.getScheduler().runTaskTimer(plugin, this::particles, 40, 20);
    }

    public void reload() {
        crates.clear();
        blockToCrate.clear();
        cfg = plugin.yaml("crates.yml");
        ConfigurationSection root = cfg.getConfigurationSection("crates");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection c = root.getConfigurationSection(id);
                List<Reward> rewards = new ArrayList<>();
                double total = 0;
                ConfigurationSection rs = c.getConfigurationSection("rewards");
                if (rs != null) {
                    for (String rid : rs.getKeys(false)) {
                        ConfigurationSection r = rs.getConfigurationSection(rid);
                        double ch = Math.max(0, r.getDouble("chance", 1));
                        if (ch <= 0) continue;
                        total += ch;
                        rewards.add(new Reward(rid, ch, Items.fromConfig(r.getConfigurationSection("display")), r.getConfigurationSection("reward"), r.getBoolean("broadcast", false)));
                    }
                }
                Material km = Material.matchMaterial(c.getString("key-material", "TRIPWIRE_HOOK"));
                crates.put(id.toLowerCase(Locale.ROOT), new Crate(id.toLowerCase(Locale.ROOT), c.getString("name", id), km == null ? Material.TRIPWIRE_HOOK : km, rewards, total));
            }
        }
        ConfigurationSection locs = cfg.getConfigurationSection("locations");
        if (locs != null) for (String k : locs.getKeys(false)) blockToCrate.put(locs.getString(k), k.split("__")[0]);
        Bukkit.getScheduler().runTask(plugin, this::spawnHolograms);
    }

    public String displayName(String crate) {
        Crate c = crates.get(crate.toLowerCase(Locale.ROOT));
        return c == null ? crate : c.name;
    }

    public List<String> keyLore(Player p) {
        PlayerData d = plugin.data().get(p);
        List<String> out = new ArrayList<>();
        for (Crate c : crates.values()) out.add(c.name + "&7: &f" + d.keys(c.id));
        return out;
    }

    private static String key(Location l) {
        return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
    }

    // ---------------- Schluessel ----------------

    public ItemStack keyItem(Crate c, int amount) {
        ItemStack it = Items.of(c.keyMaterial, amount, c.name + " &7Schluessel", List.of("&7Rechtsklick auf die Kiste am Spawn", "&7oder &f/crate open " + c.id));
        it.editMeta(m -> {
            m.getPersistentDataContainer().set(keyTag, PersistentDataType.STRING, c.id);
            m.setEnchantmentGlintOverride(true);
        });
        return it;
    }

    /** Virtuelle Schluessel; offline Spieler erhalten physische Schluessel ins Abholfach (/ah collect). */
    public void giveKeys(UUID id, String crate, int n) {
        Crate c = crates.get(crate.toLowerCase(Locale.ROOT));
        if (c == null || n <= 0) return;
        PlayerData d = plugin.data().getIfLoaded(id);
        if (d != null) {
            d.keys.merge(c.id, n, Integer::sum);
            d.dirty = true;
        } else {
            plugin.auction().mail(id, keyItem(c, Math.min(64, n)), "Schluessel");
        }
    }

    private boolean takeKey(Player p, Crate c) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!hand.getType().isAir() && c.id.equals(hand.getItemMeta() == null ? null
                : hand.getItemMeta().getPersistentDataContainer().get(keyTag, PersistentDataType.STRING))) {
            hand.setAmount(hand.getAmount() - 1);
            return true;
        }
        PlayerData d = plugin.data().get(p);
        if (d.keys(c.id) <= 0) return false;
        d.keys.put(c.id, d.keys(c.id) - 1);
        d.dirty = true;
        return true;
    }

    // ---------------- Befehle ----------------

    private void cmd(CommandSender s, String l, String[] a) {
        if (a.length == 0) { if (s instanceof Player p) openOverview(p); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("givekey")) {
            if (!s.hasPermission("minesmp.admin.crate") || a.length < 4) { Msg.send(s, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/crate givekey <Spieler|all> <Crate> <Anzahl> [physical]"); return; }
            Crate c = crates.get(a[2].toLowerCase(Locale.ROOT));
            int n;
            try { n = Integer.parseInt(a[3]); } catch (NumberFormatException e) { n = -1; }
            if (c == null || n <= 0 || n > 10_000) { Msg.send(s, "crate.bad-args", "{prefix}&cUngueltige Crate oder Anzahl."); return; }
            boolean physical = a.length > 4 && a[4].equalsIgnoreCase("physical");
            List<Player> targets = new ArrayList<>();
            if (a[1].equalsIgnoreCase("all")) targets.addAll(Bukkit.getOnlinePlayers());
            else {
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    var op = Bukkit.getOfflinePlayerIfCached(a[1]);
                    if (op == null) { Msg.send(s, "player-unknown", "{prefix}&cSpieler &f{name} &cwar noch nie auf MineSMP.", "name", a[1]); return; }
                    giveKeys(op.getUniqueId(), c.id, n);
                    Msg.send(s, "crate.given-offline", "{prefix}&aSchluessel liegen im Abholfach von &f{name}&a.", "name", a[1]);
                    return;
                }
                targets.add(t);
            }
            for (Player t : targets) {
                if (physical) Items.giveOrDrop(t, keyItem(c, n));
                else giveKeys(t.getUniqueId(), c.id, n);
                Msg.send(t, "crate.key-received", "{prefix}&7Du hast &e{n}x {crate} &7Schluessel erhalten!", "n", String.valueOf(n), "crate", c.name);
            }
            plugin.audit().log(s, "GIVE_KEY", a[1] + " " + n + "x " + c.id);
            return;
        }
        if (!(s instanceof Player p)) return;
        if (sub.equals("setblock") || sub.equals("removeblock")) {
            if (!p.hasPermission("minesmp.admin.crate")) return;
            Block b = p.getTargetBlockExact(6);
            if (b == null) { Msg.send(p, "crate.look-block", "{prefix}&cSchau auf einen Block."); return; }
            String k = key(b.getLocation());
            if (sub.equals("removeblock")) {
                blockToCrate.remove(k);
            } else {
                if (a.length < 2 || !crates.containsKey(a[1].toLowerCase(Locale.ROOT))) { Msg.send(p, "crate.unknown", "{prefix}&cUnbekannte Crate."); return; }
                blockToCrate.put(k, a[1].toLowerCase(Locale.ROOT));
            }
            saveLocations();
            spawnHolograms();
            Msg.send(p, "crate.block-updated", "{prefix}&aCrate-Block aktualisiert.");
            return;
        }
        Crate c = a.length > 1 ? crates.get(a[1].toLowerCase(Locale.ROOT)) : null;
        if (c == null) { Msg.send(p, "crate.unknown", "{prefix}&cUnbekannte Crate."); return; }
        if (sub.equals("preview")) preview(p, c);
        else if (sub.equals("open")) {
            if (!cfg.getBoolean("open-anywhere", true) && !p.hasPermission("minesmp.crate.anywhere")) {
                Msg.send(p, "crate.go-spawn", "{prefix}&cOeffne Kisten am Spawn (&f/spawn&c).");
                return;
            }
            open(p, c);
        }
    }

    private void saveLocations() {
        cfg.set("locations", null);
        int i = 0;
        for (var e : blockToCrate.entrySet()) cfg.set("locations." + e.getValue() + "__" + (i++), e.getKey());
        try {
            cfg.save(new File(plugin.getDataFolder(), "crates.yml"));
        } catch (IOException ex) {
            plugin.getLogger().warning("crates.yml konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }

    // ---------------- Bloecke / Hologramme ----------------

    private void spawnHolograms() {
        for (var w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntitiesByClass(TextDisplay.class)) if (e.getPersistentDataContainer().has(holoTag)) e.remove();
        }
        for (var e : blockToCrate.entrySet()) {
            Location l = PlayerData.locFromString(e.getKey());
            Crate c = crates.get(e.getValue());
            if (l == null || c == null) continue;
            l.add(0.5, 1.6, 0.5);
            l.getWorld().spawn(l, TextDisplay.class, td -> {
                td.text(Text.c(c.name + "\n&7Rechtsklick: &fOeffnen\n&7Linksklick: &fVorschau"));
                td.setBillboard(Display.Billboard.CENTER);
                td.setPersistent(false);
                td.getPersistentDataContainer().set(holoTag, PersistentDataType.BYTE, (byte) 1);
            });
        }
    }

    private void particles() {
        for (String k : blockToCrate.keySet()) {
            Location l = PlayerData.locFromString(k);
            if (l == null || !l.isChunkLoaded()) continue;
            l.getWorld().spawnParticle(Particle.END_ROD, l.add(0.5, 1.1, 0.5), 3, 0.3, 0.2, 0.3, 0.01);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        String id = blockToCrate.get(key(e.getClickedBlock().getLocation()));
        if (id == null) return;
        Crate c = crates.get(id);
        if (c == null) return;
        e.setCancelled(true);
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) preview(e.getPlayer(), c);
        else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) open(e.getPlayer(), c);
    }

    // ---------------- Menues ----------------

    private void openOverview(Player p) {
        PlayerData d = plugin.data().get(p);
        Menu m = new Menu(3, "&8Kisten & Schluessel") {};
        int slot = 11;
        for (Crate c : crates.values()) {
            if (slot > 15) break;
            m.set(slot++, Items.of(Material.CHEST, 1, c.name, List.of("&7Schluessel: &f" + d.keys(c.id), "", "&bLinksklick &7» Oeffnen", "&eRechtsklick &7» Vorschau")), e -> {
                if (e.isRightClick()) preview(p, c);
                else if (cfg.getBoolean("open-anywhere", true) || p.hasPermission("minesmp.crate.anywhere")) open(p, c);
                else { Menu.close(p); Msg.send(p, "crate.go-spawn", "{prefix}&cOeffne Kisten am Spawn (&f/spawn&c)."); }
            });
        }
        m.fillEmpty();
        m.open(p);
    }

    public void preview(Player p, Crate c) {
        Menu m = new Menu(6, "&8Vorschau: " + c.name) {};
        int i = 0;
        for (Reward r : c.rewards) {
            if (i >= 54) break;
            ItemStack d = r.display.clone();
            double pct = r.chance / c.totalWeight * 100;
            d.editMeta(meta -> {
                List<net.kyori.adventure.text.Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Text.item(""));
                lore.add(Text.item("&7Chance: &b" + String.format(Locale.GERMANY, "%.2f", pct) + "%"));
                meta.lore(lore);
            });
            m.set(i++, d);
        }
        m.open(p);
    }

    private Reward roll(Crate c) {
        double r = ThreadLocalRandom.current().nextDouble() * c.totalWeight;
        for (Reward rw : c.rewards) {
            r -= rw.chance;
            if (r <= 0) return rw;
        }
        return c.rewards.get(c.rewards.size() - 1);
    }

    public void open(Player p, Crate c) {
        if (c.rewards.isEmpty()) return;
        if (!plugin.cooldowns().tryUse(p.getUniqueId(), "crate", 600)) return;
        if (!takeKey(p, c)) {
            Msg.send(p, "crate.no-key", "{prefix}&cDu hast keinen {crate} &cSchluessel. &7(&f/vote&7, &f/daily&7, Events)", "crate", c.name);
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1);
            return;
        }
        Reward win = roll(c);
        plugin.data().get(p).addStat("crates_opened", 1);
        new Animation(p, c, win).start();
    }

    private void grant(Player p, Crate c, Reward win) {
        plugin.rewards().give(p, win.reward, Text.strip(c.name));
        double pct = win.chance / c.totalWeight * 100;
        if (win.broadcast || pct <= cfg.getDouble("broadcast-below-percent", 2.0)) {
            Msg.broadcast("crate.broadcast", "{prefix}&f{name} &7hat &6&l{reward} &7aus der {crate} &7gewonnen! &8({pct}%)",
                    "name", p.getName(), "reward", Items.name(win.display), "crate", c.name, "pct", String.format(Locale.GERMANY, "%.2f", pct));
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, 0.5, 1, 0.5, 0.3);
        }
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.4f);
    }

    /** Roll-Animation: mittlere Reihe laeuft durch und bremst ab; Slot 13 zeigt den Gewinn. */
    private final class Animation extends Menu {
        private final Player p;
        private final Crate c;
        private final Reward win;
        private boolean granted;
        private final List<ItemStack> strip = new ArrayList<>();

        Animation(Player p, Crate c, Reward win) {
            super(3, "&8Oeffne " + c.name + "&8...");
            this.p = p; this.c = c; this.win = win;
            for (int i = 0; i < 9; i++) {
                set(i, Items.of(i == 4 ? Material.HOPPER : Material.LIGHT_BLUE_STAINED_GLASS_PANE, " "));
                set(18 + i, Items.of(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " "));
            }
            for (int i = 0; i < 40; i++) strip.add(roll(c).display);
            strip.add(win.display); // Index 40 landet am Ende in der Mitte
            for (int i = 0; i < 4; i++) strip.add(roll(c).display);
        }

        void start() {
            open(p);
            new BukkitRunnable() {
                int offset = 0, tick = 0, delay = 1, wait = 0;
                @Override public void run() {
                    if (granted || !p.isOnline()) { cancel(); return; }
                    if (wait-- > 0) return;
                    for (int i = 0; i < 9; i++) inv.setItem(9 + i, strip.get(offset + i));
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.8f);
                    if (offset + 4 == 40) {
                        cancel();
                        finish();
                        return;
                    }
                    offset++;
                    tick++;
                    if (tick > 20) delay = 2;
                    if (tick > 28) delay = 3;
                    if (tick > 34) delay = 5;
                    wait = delay - 1;
                }
            }.runTaskTimer(plugin, 2, 1);
        }

        private void finish() {
            if (granted) return;
            granted = true;
            inv.setItem(13, Items.glow(win.display.clone()));
            grant(p, c, win);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.getOpenInventory().getTopInventory() == inv) p.closeInventory();
            }, 40);
        }

        @Override
        public void handleClick(InventoryClickEvent e) {
            e.setCancelled(true);
        }

        @Override
        public void onClose(InventoryCloseEvent e) {
            if (!granted) { granted = true; grant(p, c, win); }
        }
    }
}
