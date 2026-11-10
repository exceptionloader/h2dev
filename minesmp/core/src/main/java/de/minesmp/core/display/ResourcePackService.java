package de.minesmp.core.display;

import com.sun.net.httpserver.HttpServer;
import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Liefert das MineSMP-Ressourcenpaket (Logo, Kaestchen, Icons als Schriftzeichen) ueber einen
 * eingebauten Webserver aus und merkt sich, wer es geladen hat. Ohne Paket zeigt das Scoreboard die Text-Version.
 * Kuerzel wie :logo: :coin: werden in Texten durch die Zeichen ersetzt (glyphs.yml im Plugin).
 */
public final class ResourcePackService implements Listener {
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("minesmp-pack".getBytes(StandardCharsets.UTF_8));
    private final MineSMP plugin;
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private final Map<String, String> glyphs = new HashMap<>();
    private HttpServer http;
    private byte[] packBytes;
    private byte[] sha1;
    private String url;

    public ResourcePackService(MineSMP plugin) {
        this.plugin = plugin;
        try (InputStream in = plugin.getResource("glyphs.yml")) {
            if (in != null) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String k : y.getKeys(false)) glyphs.put(":" + k + ":", y.getString(k));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("glyphs.yml fehlt: " + e.getMessage());
        }
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("resource-pack.enabled", true)) return;
        try {
            File f = new File(plugin.getDataFolder(), "minesmp-pack.zip");
            if (!f.exists() || !cfg.getBoolean("resource-pack.keep-custom-file", false)) plugin.saveResource("minesmp-pack.zip", true);
            packBytes = Files.readAllBytes(f.toPath());
            sha1 = MessageDigest.getInstance("SHA-1").digest(packBytes);
            String external = cfg.getString("resource-pack.url", "");
            int port = cfg.getInt("resource-pack.port", 8163);
            if (external != null && !external.isBlank()) {
                url = external;
            } else {
                http = HttpServer.create(new InetSocketAddress(port), 0);
                http.createContext("/minesmp-pack.zip", ex -> {
                    ex.getResponseHeaders().add("Content-Type", "application/zip");
                    ex.sendResponseHeaders(200, packBytes.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(packBytes); }
                });
                http.setExecutor(Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "MineSMP-Pack"); t.setDaemon(true); return t; }));
                http.start();
                url = "http://" + cfg.getString("resource-pack.host", "localhost") + ":" + port + "/minesmp-pack.zip";
            }
            plugin.listen(this);
            plugin.getLogger().info("Ressourcenpaket bereit: " + url + " (" + packBytes.length / 1024 + " KB)");
        } catch (Exception e) {
            plugin.getLogger().warning("Ressourcenpaket konnte nicht gestartet werden: " + e.getMessage() + " - Scoreboard nutzt die Text-Version.");
        }
    }

    public boolean has(Player p) {
        return loaded.contains(p.getUniqueId());
    }

    /** Ersetzt :kuerzel: durch Zeichen aus dem Ressourcenpaket. */
    public String glyphs(String s) {
        if (s == null || s.indexOf(':') < 0) return s;
        for (var e : glyphs.entrySet()) if (s.contains(e.getKey())) s = s.replace(e.getKey(), e.getValue());
        return s;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || url == null) return;
            p.setResourcePack(PACK_ID, url, sha1, Text.c(plugin.getConfig().getString("resource-pack.prompt",
                    "&b&lMINE&f&lSMP &7- Lade das Paket fuer Scoreboard-Design und Icons.")), plugin.getConfig().getBoolean("resource-pack.required", false));
        }, 20);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        Player p = e.getPlayer();
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> { loaded.add(p.getUniqueId()); plugin.scoreboard().update(p); plugin.scoreboard().refreshNametags(); }
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> {
                loaded.remove(p.getUniqueId());
                if (e.getStatus() != PlayerResourcePackStatusEvent.Status.DECLINED)
                    plugin.getLogger().warning("Ressourcenpaket fuer " + p.getName() + ": " + e.getStatus() + " (URL " + url + ")");
            }
            default -> { }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        loaded.remove(e.getPlayer().getUniqueId());
    }

    public void shutdown() {
        if (http != null) http.stop(0);
    }
}
