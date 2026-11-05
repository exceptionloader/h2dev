package de.minesmp.core.util;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zentrale Nachrichtenverwaltung (messages.yml).
 * Jede Nachricht hat einen Schluessel und einen deutschen Standardtext im Code.
 * Fehlt ein Schluessel in messages.yml, wird der Standardtext verwendet und ergaenzt.
 * {prefix} wird durch "prefix" ersetzt, weitere {platzhalter} per Key/Value-Paaren.
 */
public final class Msg {
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static YamlConfiguration yml = new YamlConfiguration();
    private static File file;
    private static volatile boolean dirty;

    private Msg() {}

    public static void load(JavaPlugin plugin) {
        file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        yml = YamlConfiguration.loadConfiguration(file);
        CACHE.clear();
    }

    public static void saveIfDirty() {
        if (!dirty || file == null) return;
        try {
            yml.save(file);
            dirty = false;
        } catch (IOException ignored) {
        }
    }

    public static String raw(String key, String def) {
        return CACHE.computeIfAbsent(key, k -> {
            String v = yml.getString(k);
            if (v == null) {
                yml.set(k, def);
                dirty = true;
                v = def;
            }
            return v;
        });
    }

    public static String get(String key, String def, String... kv) {
        String s = raw(key, def);
        s = s.replace("{prefix}", raw("prefix", "&8[&b&lMine&f&lSMP&8] &7"));
        return Text.replace(s, kv);
    }

    public static void send(CommandSender to, String key, String def, String... kv) {
        if (to == null) return;
        String s = get(key, def, kv);
        if (s.isEmpty()) return;
        for (String line : s.split("\\\\n|\n")) to.sendMessage(Text.c(line));
    }

    public static void broadcast(String key, String def, String... kv) {
        String s = get(key, def, kv);
        if (s.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) for (String line : s.split("\\\\n|\n")) p.sendMessage(Text.c(line));
        Bukkit.getConsoleSender().sendMessage(Text.c(s));
    }

    private static final Map<java.util.UUID, Long> AB_BUSY = new ConcurrentHashMap<>();

    public static void actionbar(Player p, String key, String def, String... kv) {
        markActionbar(p, 3000);
        p.sendActionBar(Text.c(get(key, def, kv)));
    }

    /** Haelt die dauerhafte Info-Actionbar fuer kurze Zeit frei (andere Meldung hat Vorrang). */
    public static void markActionbar(Player p, long ms) {
        AB_BUSY.put(p.getUniqueId(), System.currentTimeMillis() + ms);
    }

    public static boolean actionbarBusy(Player p) {
        Long t = AB_BUSY.get(p.getUniqueId());
        if (t == null) return false;
        if (t < System.currentTimeMillis()) { AB_BUSY.remove(p.getUniqueId()); return false; }
        return true;
    }
}
