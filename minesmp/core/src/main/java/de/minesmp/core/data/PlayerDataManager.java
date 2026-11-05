package de.minesmp.core.data;

import de.minesmp.core.MineSMP;
import de.minesmp.core.db.Database;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** Laedt Spielerdaten beim Login (asynchron), speichert beim Verlassen und regelmaessig. */
public final class PlayerDataManager implements Listener {
    private final MineSMP plugin;
    private final Database db;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();

    public PlayerDataManager(MineSMP plugin) {
        this.plugin = plugin;
        this.db = plugin.db();
        long interval = 20L * 60 * plugin.getConfig().getInt("database.autosave-minutes", 5);
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveAll, interval, interval);
    }

    public PlayerData get(Player p) {
        PlayerData d = cache.get(p.getUniqueId());
        if (d == null) {
            // Sollte nicht passieren (Login laedt immer). Fallback: leere Daten, damit nichts abstuerzt.
            d = new PlayerData(p.getUniqueId(), p.getName());
            cache.put(p.getUniqueId(), d);
            plugin.getLogger().warning("Spielerdaten fuer " + p.getName() + " fehlten im Cache!");
        }
        return d;
    }

    /** Nur fuer online Spieler, sonst null. */
    public PlayerData getIfLoaded(UUID id) {
        return cache.get(id);
    }

    public Collection<PlayerData> online() {
        return cache.values();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        UUID id = e.getUniqueId();
        if (cache.containsKey(id)) return; // Doppel-Login: vorhandene Daten weiterverwenden
        try {
            PlayerData d = db.query(c -> load(c, id, e.getName())).get(10, TimeUnit.SECONDS);
            d.name = e.getName();
            d.lastIp = e.getAddress().getHostAddress();
            cache.put(id, d);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Spielerdaten fuer " + e.getName() + " konnten nicht geladen werden", ex);
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Text.c("&cDeine Daten konnten nicht geladen werden. Bitte versuche es gleich erneut."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        PlayerData d = get(e.getPlayer());
        if (d.firstJoin == 0) {
            d.firstJoin = System.currentTimeMillis();
            d.dirty = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        PlayerData d = cache.get(p.getUniqueId());
        if (d == null) return;
        save(d, p);
        UUID id = p.getUniqueId();
        // Erst im naechsten Tick entfernen und nur, wenn der Spieler nicht direkt wieder online ist.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (Bukkit.getPlayer(id) == null) cache.remove(id);
        });
    }

    public void loadOnlineSync() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                PlayerData d = db.query(c -> load(c, p.getUniqueId(), p.getName())).get(10, TimeUnit.SECONDS);
                cache.put(p.getUniqueId(), d);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE, "Konnte " + p.getName() + " nicht laden", ex);
            }
        }
    }

    private PlayerData load(Connection c, UUID id, String name) throws SQLException {
        PlayerData d = new PlayerData(id, name);
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM players WHERE uuid = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    d.balance = rs.getDouble("balance");
                    d.kills = rs.getInt("kills");
                    d.deaths = rs.getInt("deaths");
                    d.level = Math.max(1, rs.getInt("level"));
                    d.playtime = rs.getLong("playtime");
                    d.lastIp = rs.getString("last_ip");
                    d.deserialize(rs.getString("data"));
                } else {
                    d.balance = plugin.getConfig().getDouble("economy.start-balance", 500);
                    d.dirty = true;
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO players (uuid, name, balance, last_seen, data) VALUES (?, ?, ?, ?, ?)")) {
                        Database.bind(ins, id.toString(), name, d.balance, System.currentTimeMillis(), d.serialize());
                        ins.executeUpdate();
                    }
                }
            }
        }
        return d;
    }

    /** Snapshot auf dem Hauptthread erstellen, asynchron schreiben. */
    public void save(PlayerData d, Player online) {
        if (online != null) d.playtime = online.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L * 1000L;
        Object[] row = {d.name, d.balance, d.kills, d.deaths, d.level, d.playtime, d.lastIp, System.currentTimeMillis(), d.serialize(), d.uuid.toString()};
        d.dirty = false;
        db.update("UPDATE players SET name=?, balance=?, kills=?, deaths=?, level=?, playtime=?, last_ip=?, last_seen=?, data=? WHERE uuid=?", row);
    }

    public void saveAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = cache.get(p.getUniqueId());
            if (d != null) save(d, p);
        }
    }

    /** Offline-Gutschrift (z.B. Auktionsverkauf). Online-Spieler werden direkt im Cache geaendert. */
    public void addBalanceOffline(UUID id, double amount) {
        PlayerData d = cache.get(id);
        if (d != null) {
            d.balance = Math.max(0, d.balance + amount);
            d.dirty = true;
            return;
        }
        db.update("UPDATE players SET balance = balance + ? WHERE uuid = ?", amount, id.toString());
    }

    public record TopEntry(String name, double value) {}

    /** Rangliste direkt aus der Datenbank (column ist intern festgelegt, kein User-Input). */
    public void top(String column, int limit, java.util.function.Consumer<List<TopEntry>> onMain) {
        if (!List.of("balance", "kills", "deaths", "level", "playtime").contains(column)) throw new IllegalArgumentException(column);
        saveAll();
        db.queryThen(c -> {
            List<TopEntry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name, " + column + " FROM players ORDER BY " + column + " DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new TopEntry(rs.getString(1), rs.getDouble(2)));
                }
            }
            return out;
        }, onMain);
    }
}
