package de.minesmp.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.minesmp.core.MineSMP;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Datenbankzugriff ueber HikariCP. Unterstuetzt SQLite (Standard) und MariaDB.
 * Alle Zugriffe laufen ueber einen eigenen Single-Thread-Executor:
 * - nie blockierend auf dem Hauptthread
 * - garantierte Reihenfolge (z.B. Offline-Gutschrift vor dem Laden beim Join)
 */
public final class Database {
    public interface SqlFunction<T> { T apply(Connection c) throws SQLException; }
    public interface SqlConsumer { void accept(Connection c) throws SQLException; }

    private final MineSMP plugin;
    private HikariDataSource ds;
    private boolean mariadb;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MineSMP-DB");
        t.setDaemon(true);
        return t;
    });

    public Database(MineSMP plugin) {
        this.plugin = plugin;
    }

    public void connect() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("database");
        String type = s == null ? "sqlite" : s.getString("type", "sqlite");
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("MineSMP-Pool");
        if ("mariadb".equalsIgnoreCase(type) || "mysql".equalsIgnoreCase(type)) {
            mariadb = true;
            hc.setDriverClassName("org.mariadb.jdbc.Driver");
            hc.setJdbcUrl("jdbc:mariadb://" + s.getString("host", "localhost") + ":" + s.getInt("port", 3306)
                    + "/" + s.getString("name", "minesmp") + "?useUnicode=true&characterEncoding=utf8");
            hc.setUsername(s.getString("user", "minesmp"));
            hc.setPassword(s.getString("password", ""));
            hc.setMaximumPoolSize(s.getInt("pool-size", 6));
        } else {
            File f = new File(plugin.getDataFolder(), s == null ? "minesmp.db" : s.getString("file", "minesmp.db"));
            hc.setDriverClassName("org.sqlite.JDBC");
            hc.setJdbcUrl("jdbc:sqlite:" + f.getAbsolutePath());
            hc.setMaximumPoolSize(1);
        }
        hc.setConnectionTimeout(10_000);
        ds = new HikariDataSource(hc);
        createSchema();
    }

    private String ai() { return mariadb ? "INTEGER PRIMARY KEY AUTO_INCREMENT" : "INTEGER PRIMARY KEY AUTOINCREMENT"; }
    private String text() { return mariadb ? "LONGTEXT" : "TEXT"; }

    private void createSchema() {
        String[] sql = {
            "CREATE TABLE IF NOT EXISTS players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL, balance DOUBLE NOT NULL DEFAULT 0,"
                + " kills INTEGER NOT NULL DEFAULT 0, deaths INTEGER NOT NULL DEFAULT 0, level INTEGER NOT NULL DEFAULT 1,"
                + " playtime BIGINT NOT NULL DEFAULT 0, last_ip VARCHAR(64), last_seen BIGINT NOT NULL DEFAULT 0, data " + text() + ")",
            "CREATE TABLE IF NOT EXISTS transactions (id " + ai() + ", time BIGINT NOT NULL, source VARCHAR(36), target VARCHAR(36),"
                + " amount DOUBLE NOT NULL, type VARCHAR(32) NOT NULL, info VARCHAR(255))",
            "CREATE TABLE IF NOT EXISTS clans (id VARCHAR(36) PRIMARY KEY, name VARCHAR(32) NOT NULL, tag VARCHAR(16) NOT NULL, data " + text() + ")",
            "CREATE TABLE IF NOT EXISTS auctions (id " + ai() + ", seller VARCHAR(36) NOT NULL, seller_name VARCHAR(16), item " + text() + " NOT NULL,"
                + " price DOUBLE NOT NULL, created BIGINT NOT NULL, expires BIGINT NOT NULL, status VARCHAR(16) NOT NULL, buyer VARCHAR(36), search VARCHAR(255))",
            "CREATE TABLE IF NOT EXISTS mailbox (id " + ai() + ", owner VARCHAR(36) NOT NULL, item " + text() + " NOT NULL, created BIGINT NOT NULL, reason VARCHAR(64))",
            "CREATE TABLE IF NOT EXISTS punishments (id " + ai() + ", target VARCHAR(36) NOT NULL, target_name VARCHAR(16), type VARCHAR(16) NOT NULL,"
                + " reason VARCHAR(255), staff VARCHAR(36), staff_name VARCHAR(16), created BIGINT NOT NULL, expires BIGINT NOT NULL, active INTEGER NOT NULL DEFAULT 1, ip VARCHAR(64))",
            "CREATE TABLE IF NOT EXISTS reports (id " + ai() + ", reporter VARCHAR(36), reporter_name VARCHAR(16), target VARCHAR(36), target_name VARCHAR(16),"
                + " reason VARCHAR(255), created BIGINT NOT NULL, status VARCHAR(16) NOT NULL, handler VARCHAR(16), location VARCHAR(128))",
            "CREATE TABLE IF NOT EXISTS audit_log (id " + ai() + ", time BIGINT NOT NULL, actor VARCHAR(16), action VARCHAR(32) NOT NULL, details VARCHAR(512))",
            "CREATE TABLE IF NOT EXISTS chest_shops (loc VARCHAR(128) PRIMARY KEY, owner VARCHAR(36) NOT NULL, owner_name VARCHAR(16), item " + text() + " NOT NULL,"
                + " amount INTEGER NOT NULL, buy DOUBLE NOT NULL, sell DOUBLE NOT NULL)",
            "CREATE TABLE IF NOT EXISTS kv (k VARCHAR(128) PRIMARY KEY, v " + text() + ")",
            "CREATE TABLE IF NOT EXISTS guard_cases (id VARCHAR(16) PRIMARY KEY, time BIGINT NOT NULL, uuid VARCHAR(36) NOT NULL, name VARCHAR(16), kind VARCHAR(32),"
                + " confidence DOUBLE NOT NULL, action VARCHAR(16) NOT NULL, evidence " + text() + ", ai " + text() + ")",
            "CREATE INDEX IF NOT EXISTS idx_auction_status ON auctions(status)",
            "CREATE INDEX IF NOT EXISTS idx_mail_owner ON mailbox(owner)",
            "CREATE INDEX IF NOT EXISTS idx_pun_target ON punishments(target)",
            "CREATE INDEX IF NOT EXISTS idx_players_name ON players(name)"
        };
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            if (!mariadb) { st.execute("PRAGMA journal_mode=WAL"); st.execute("PRAGMA synchronous=NORMAL"); }
            for (String q : sql) st.execute(q);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Datenbankschema konnte nicht erstellt werden", e);
            throw new IllegalStateException(e);
        }
    }

    public boolean isMariaDb() { return mariadb; }

    /** Fuehrt Arbeit asynchron aus. */
    public CompletableFuture<Void> run(SqlConsumer work) {
        return CompletableFuture.runAsync(() -> {
            try (Connection c = ds.getConnection()) {
                work.accept(c);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "SQL-Fehler", e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    public <T> CompletableFuture<T> query(SqlFunction<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = ds.getConnection()) {
                return work.apply(c);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "SQL-Fehler", e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /** Ergebnis asynchron holen, dann auf dem Hauptthread verarbeiten. */
    public <T> void queryThen(SqlFunction<T> work, Consumer<T> onMain) {
        query(work).whenComplete((res, err) -> {
            if (err != null) return;
            Bukkit.getScheduler().runTask(plugin, () -> onMain.accept(res));
        });
    }

    public void update(String sql, Object... params) {
        run(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                ps.executeUpdate();
            }
        });
    }

    public static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    }

    public static long insertId(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.getGeneratedKeys()) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    public void kvSet(String k, String v) {
        update("REPLACE INTO kv (k, v) VALUES (?, ?)", k, v);
    }

    public CompletableFuture<String> kvGet(String k) {
        return query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM kv WHERE k = ?")) {
                ps.setString(1, k);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) plugin.getLogger().warning("DB-Warteschlange nicht vollstaendig geleert!");
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (ds != null) ds.close();
    }
}
