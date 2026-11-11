package de.minesmp.core.world;

import de.minesmp.core.MineSMP;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * 3D-Modelle fuer NPCs und Bosse (config.yml -> models).
 * <ul>
 *   <li>Mit dem Plugin <b>BetterModel</b>: die Blockbench-Modelle aus dem Plugin (models/*.bbmodel) werden beim Start nach
 *       plugins/BetterModel/models kopiert, BetterModel baut daraus ein Ressourcenpaket (plugins/BetterModel/build.zip),
 *       das MineSMPCore zusammen mit dem eigenen Paket an die Spieler schickt.</li>
 *   <li>Ohne BetterModel: NPCs tragen die passenden Fantasy-Skins aus dem MineSMP-Paket, Bosse erscheinen als
 *       vergroesserte normale Monster. Alles andere funktioniert gleich.</li>
 * </ul>
 */
public final class ModelService {
    /** Alle mitgelieferten Modelle (Dateiname ohne .bbmodel). */
    public static final List<String> NPC_MODELS = List.of("daemon", "eisgolem", "goblin", "haendler", "koenig", "magier", "ork", "ritter", "skelettkrieger");
    public static final List<String> BOSS_MODELS = List.of("lavenspinne", "riesenhexe", "schattenkraken", "steintitan", "vierkopfdrache");

    private final MineSMP plugin;
    private boolean active;
    private boolean warned;
    private BetterModelBridge bridge;

    public ModelService(MineSMP plugin) {
        this.plugin = plugin;
        if (!plugin.getConfig().getBoolean("models.enabled", true)) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("BetterModel")) {
            plugin.getLogger().info("3D-Modelle: BetterModel ist nicht installiert - NPCs nutzen die Fantasy-Skins, Bosse normale Monster.");
            return;
        }
        try {
            bridge = new BetterModelBridge();
            bridge.count();
            active = true;
            plugin.getLogger().info("3D-Modelle: BetterModel gefunden - NPCs und Bosse werden als 3D-Modelle angezeigt.");
        } catch (Throwable t) { // andere, unpassende BetterModel-Version
            plugin.getLogger().warning("3D-Modelle: BetterModel passt nicht zu dieser Version von MineSMPCore (" + t + "). Getestet ist BetterModel 3.6.0.");
        }
    }

    /**
     * Wird in onLoad aufgerufen (vor dem Start von BetterModel): legt die mitgelieferten Modelle in plugins/BetterModel/models ab.
     * Vorhandene Dateien werden nur ersetzt, wenn sie von MineSMPCore stammen und sich geaendert haben.
     */
    public static void install(JavaPlugin plugin) {
        File plugins = plugin.getDataFolder().getParentFile();
        File[] jars = plugins.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).startsWith("bettermodel") && n.toLowerCase(Locale.ROOT).endsWith(".jar"));
        File folder = new File(plugins, "BetterModel/models");
        if ((jars == null || jars.length == 0) && !folder.getParentFile().isDirectory()) return;
        if (!plugin.getConfig().getBoolean("models.enabled", true)) return;
        int copied = 0;
        try {
            Files.createDirectories(folder.toPath());
            for (String name : concat()) {
                File target = new File(folder, name + ".bbmodel");
                try (InputStream in = plugin.getResource("models/" + name + ".bbmodel")) {
                    if (in == null) continue;
                    byte[] data = in.readAllBytes();
                    if (target.isFile() && java.util.Arrays.equals(Files.readAllBytes(target.toPath()), data)) continue;
                    File tmp = new File(folder, name + ".bbmodel.tmp");
                    Files.write(tmp.toPath(), data);
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }
            if (copied > 0) plugin.getLogger().info("3D-Modelle: " + copied + " Modelle nach plugins/BetterModel/models gelegt.");
        } catch (Exception e) {
            plugin.getLogger().warning("3D-Modelle konnten nicht abgelegt werden: " + e);
        }
    }

    private static List<String> concat() {
        java.util.ArrayList<String> l = new java.util.ArrayList<>(NPC_MODELS);
        l.addAll(BOSS_MODELS);
        return l;
    }

    public boolean active() { return active; }

    /** Modell, das ein Spawn-NPC tragen soll (config.yml -> models.npcs), oder null. */
    public String npcModel(String npcId) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("models.npcs");
        String m = s == null ? null : s.getString(npcId);
        return m == null || m.isBlank() || !NPC_MODELS.contains(m) ? null : m;
    }

    /** Haengt das Modell an, falls noch nicht geschehen. true = Modell sichtbar. */
    public boolean ensure(Entity e, String model, double scale) {
        if (!active || e == null || !e.isValid() || model == null) return false;
        try {
            if (bridge.attached(e)) return true;
            if (!bridge.has(model)) {
                if (!warned) {
                    warned = true;
                    plugin.getLogger().warning("3D-Modelle: Modell '" + model + "' ist in BetterModel (noch) nicht geladen. Liegt es in plugins/BetterModel/models? Sonst: /bettermodel reload");
                }
                return false;
            }
            return bridge.attach(e, model, (float) scale);
        } catch (Throwable t) {
            plugin.getLogger().warning("3D-Modell '" + model + "' konnte nicht angehaengt werden: " + t);
            return false;
        }
    }

    public void animate(Entity e, String animation) {
        if (!active || e == null) return;
        try { bridge.animate(e, animation); } catch (Throwable ignored) { }
    }

    public void detach(Entity e) {
        if (!active || e == null) return;
        try { bridge.detach(e); } catch (Throwable ignored) { }
    }

    /** Von BetterModel gebautes Ressourcenpaket (oder null, wenn es keins gibt). */
    public File packFile() {
        if (!active) return null;
        File f = new File(plugin.getDataFolder().getParentFile(), plugin.getConfig().getString("models.pack-file", "BetterModel/build.zip"));
        return f.isFile() ? f : null;
    }
}
