package de.minesmp.core.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.minesmp.core.MineSMP;
import de.minesmp.core.db.Database;
import de.minesmp.core.display.RankService;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/**
 * Website von MineSMP, laeuft direkt im Plugin (eigener kleiner Webserver, website.yml):
 * Startseite mit Live-Spielerzahl, Raenge (Kauf ueber einen Shop wie Tebex), Forum, Team, Regeln, Discord, Impressum/Datenschutz.
 * Forum-Anmeldung: im Spiel /web -> 6-stelliger Code (5 Minuten gueltig) -> auf der Website Name + Code.
 * Seiten-Dateien liegen in plugins/MineSMPCore/web (aus dem Plugin, bei Updates erneuert; website.yml keep-custom-files).
 */
public final class WebService implements Listener {
    private record Code(String code, UUID uuid, String name, String rank, boolean mod, long expires) {}
    private record Session(String token, String uuid, String name, String rank, boolean mod, long expires) {}
    private record Reply(int status, Object body) {}

    private static final Gson GSON = new Gson();
    private static final SecureRandom RNG = new SecureRandom();
    private static final String COOKIE = "msmp_session";

    private final MineSMP plugin;
    private YamlConfiguration cfg;
    private HttpServer http;
    private File webDir;
    private volatile String statusJson = "{}";
    private volatile Set<String> onlineNames = Set.of();
    private final Map<String, Code> codes = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> rate = new ConcurrentHashMap<>();

    public WebService(MineSMP plugin) {
        this.plugin = plugin;
        cfg = plugin.yaml("website.yml");
        plugin.listen(this);
        plugin.command(new SimpleCommand("web", "minesmp.web", "Code fuer die Website (Forum)", (s, l, a) -> {
            if (s instanceof Player p) code(p);
        }, "webcode", "forumcode"));
        if (!cfg.getBoolean("enabled", true)) return;
        try {
            webDir = new File(plugin.getDataFolder(), "web");
            extractFiles();
            int port = cfg.getInt("port", 8164);
            http = HttpServer.create(new InetSocketAddress(cfg.getString("bind", "0.0.0.0"), port), 0);
            http.createContext("/api/", this::api);
            http.createContext("/", this::files);
            http.setExecutor(Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "MineSMP-Web"); t.setDaemon(true); return t; }));
            http.start();
            plugin.getLogger().info("Website laeuft: " + url());
        } catch (Exception e) {
            plugin.getLogger().warning("Website konnte nicht starten: " + e.getMessage());
            http = null;
        }
        Bukkit.getScheduler().runTask(plugin, this::snapshot);
        Bukkit.getScheduler().runTaskTimer(plugin, this::snapshot, 40, 40);
        Bukkit.getScheduler().runTaskTimer(plugin, this::recordMembers, 200, 400);   // Team-Seite: Raenge alle 20 s
    }

    public String url() {
        String u = cfg.getString("url", "");
        return u.isBlank() ? "http://" + cfg.getString("server-address", "localhost") + ":" + cfg.getInt("port", 8164) + "/" : u;
    }

    public void shutdown() {
        if (http != null) http.stop(0);
    }

    // ================================================================ Seiten-Dateien

    /** Kopiert web/** aus dem Plugin in den Datenordner (bei neuer Plugin-Version erneut, ausser keep-custom-files). */
    private void extractFiles() throws IOException {
        String version = plugin.getPluginMeta().getVersion() + "-" + plugin.jarFile().lastModified();
        File marker = new File(webDir, ".version");
        boolean fresh = !marker.exists() || !Files.readString(marker.toPath()).equals(version);
        if (!fresh || (webDir.exists() && cfg.getBoolean("keep-custom-files", false))) return;
        try (JarFile jar = new JarFile(plugin.jarFile())) {
            var en = jar.entries();
            while (en.hasMoreElements()) {
                var e = en.nextElement();
                if (!e.getName().startsWith("web/") || e.isDirectory()) continue;
                File out = new File(plugin.getDataFolder(), e.getName());
                out.getParentFile().mkdirs();
                try (InputStream in = jar.getInputStream(e)) { Files.copy(in, out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            }
        }
        webDir.mkdirs();
        Files.writeString(marker.toPath(), version);
    }

    private static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8", "png", "image/png", "jpg", "image/jpeg", "svg", "image/svg+xml",
            "ico", "image/x-icon", "json", "application/json; charset=utf-8", "webp", "image/webp");

    private void files(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) { raw(ex, 405, "text/plain", new byte[0]); return; }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) path = "/index.html";
            File f = new File(webDir, path.substring(1)).getCanonicalFile();
            if (!f.getPath().startsWith(webDir.getCanonicalPath() + File.separator) || !f.isFile()) f = new File(webDir, "index.html");
            String n = f.getName();
            String type = TYPES.getOrDefault(n.substring(n.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT), "application/octet-stream");
            ex.getResponseHeaders().add("Cache-Control", n.endsWith(".html") ? "no-cache" : "public, max-age=3600");
            raw(ex, 200, type, Files.readAllBytes(f.toPath()));
        } catch (Exception e) {
            raw(ex, 500, "text/plain", "Fehler".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void raw(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "same-origin");
        h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        ex.sendResponseHeaders(status, ex.getRequestMethod().equals("HEAD") ? -1 : (body.length == 0 ? -1 : body.length));
        if (body.length > 0 && !ex.getRequestMethod().equals("HEAD")) try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        ex.close();
    }

    // ================================================================ Live-Daten (Hauptthread)

    private void snapshot() {
        JsonObject o = new JsonObject();
        o.addProperty("online", Bukkit.getOnlinePlayers().size());
        o.addProperty("max", Bukkit.getMaxPlayers());
        o.addProperty("address", cfg.getString("server-address", "minesmp.de"));
        o.addProperty("version", cfg.getString("version-label", "Java 26.3"));
        var players = new com.google.gson.JsonArray();
        Set<String> names = new java.util.HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.vanish() != null && plugin.vanish().isVanished(p)) continue;
            JsonObject j = new JsonObject();
            j.addProperty("name", p.getName());
            j.addProperty("rank", plugin.ranks() == null ? "default" : plugin.ranks().rankOf(p).id());
            players.add(j);
            names.add(p.getName().toLowerCase(Locale.ROOT));
        }
        o.add("players", players);
        if (plugin.events() != null) {
            var n = plugin.events().nextEvent();
            if (n != null) { JsonObject e = new JsonObject(); e.addProperty("name", Text.strip(n.name())); e.addProperty("in", n.inMillis()); o.add("nextEvent", e); }
        }
        statusJson = GSON.toJson(o);
        onlineNames = names;
    }

    /** Merkt sich Rang und Namen aktiver Spieler (fuer die Team-Seite, ohne Abhaengigkeit von LuckPerms). */
    private void recordMembers() {
        long now = System.currentTimeMillis();
        List<Object[]> rows = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers())
            rows.add(new Object[]{p.getUniqueId().toString(), p.getName(), plugin.ranks() == null ? "default" : plugin.ranks().rankOf(p).id(), now});
        if (rows.isEmpty()) return;
        plugin.db().run(c -> {
            for (Object[] r : rows) {
                try (PreparedStatement d = c.prepareStatement("DELETE FROM web_members WHERE uuid = ?")) { Database.bind(d, r[0]); d.executeUpdate(); }
                try (PreparedStatement i = c.prepareStatement("INSERT INTO web_members (uuid, name, rank_id, last_seen) VALUES (?,?,?,?)")) { Database.bind(i, r); i.executeUpdate(); }
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, this::recordMembers, 60L);
    }

    // ================================================================ Anmeldung

    private void code(Player p) {
        recordMembers();
        String c = String.format("%06d", RNG.nextInt(1_000_000));
        String rank = plugin.ranks() == null ? "default" : plugin.ranks().rankOf(p).id();
        codes.put(p.getName().toLowerCase(Locale.ROOT), new Code(c, p.getUniqueId(), p.getName(), rank, p.hasPermission("minesmp.web.moderate"),
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)));
        Msg.send(p, "web.code", "{prefix}&7Dein Website-Code: &b&l{code} &8(5 Minuten gueltig)\n{prefix}&7Auf &f{url} &7oben &fAnmelden &7- Name + Code eingeben.",
                "code", c, "url", url());
    }

    private Session session(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        String token = null;
        for (String part : cookie.split(";")) {
            String t = part.trim();
            if (t.startsWith(COOKIE + "=")) token = t.substring(COOKIE.length() + 1);
        }
        if (token == null || !token.matches("[0-9a-f]{64}")) return null;
        Session s = sessions.get(token);
        if (s == null) {
            final String tk = token;
            s = q(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, rank_id, moderator, expires FROM web_sessions WHERE token = ?")) {
                    ps.setString(1, tk);
                    ResultSet rs = ps.executeQuery();
                    return rs.next() ? new Session(tk, rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1, rs.getLong(5)) : null;
                }
            });
            if (s != null) sessions.put(token, s);
        }
        if (s != null && s.expires < System.currentTimeMillis()) { sessions.remove(token); return null; }
        return s;
    }

    private boolean limited(String key, int max, long windowMs) {
        long now = System.currentTimeMillis();
        Deque<Long> d = rate.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (d) {
            while (!d.isEmpty() && d.peekFirst() < now - windowMs) d.pollFirst();
            if (d.size() >= max) return true;
            d.addLast(now);
            return false;
        }
    }

    private String ip(HttpExchange ex) {
        String fwd = cfg.getBoolean("behind-proxy", false) ? ex.getRequestHeaders().getFirst("X-Forwarded-For") : null;
        return fwd != null ? fwd.split(",")[0].trim() : ex.getRemoteAddress().getAddress().getHostAddress();
    }

    // ================================================================ API

    private <T> T q(Database.SqlFunction<T> f) {
        try { return plugin.db().query(f).get(5, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void api(HttpExchange ex) throws IOException {
        Reply r;
        try {
            String path = ex.getRequestURI().getPath().substring(4);   // nach "/api"
            String method = ex.getRequestMethod();
            if (method.equals("POST")) {
                // Nur JSON und nur mit eigenem Kopfzeilen-Feld: schuetzt vor fremden Formularen (CSRF)
                String ct = ex.getRequestHeaders().getFirst("Content-Type");
                if (ct == null || !ct.startsWith("application/json") || !"1".equals(ex.getRequestHeaders().getFirst("X-MineSMP"))) { r = new Reply(403, err("Ungültige Anfrage")); send(ex, r); return; }
            }
            r = route(ex, method, path);
        } catch (Exception e) {
            plugin.getLogger().warning("Website-API: " + e.getMessage());
            r = new Reply(500, err("Serverfehler - bitte später erneut versuchen."));
        }
        send(ex, r);
    }

    private static Map<String, Object> err(String m) { return Map.of("error", m); }

    private void send(HttpExchange ex, Reply r) throws IOException {
        byte[] b = (r.body instanceof String s ? s : GSON.toJson(r.body)).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        raw(ex, r.status, "application/json; charset=utf-8", b);
    }

    private JsonObject body(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(20_000);
        try { return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (Exception e) { return new JsonObject(); }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    /** Text bereinigen: Steuerzeichen raus (Zeilenumbrueche bleiben), Laenge begrenzen. */
    private static String clean(String s, int max) {
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(cp -> { if (cp == '\n' || cp >= 32 && cp != 127) b.appendCodePoint(cp); });
        String t = b.toString().replace("\r", "").strip();
        t = t.replaceAll("\n{4,}", "\n\n\n");
        return t.length() > max ? t.substring(0, max) : t;
    }

    private Reply route(HttpExchange ex, String method, String path) throws IOException {
        Session me = session(ex);
        switch (method + " " + path) {
            case "GET /status": return new Reply(200, statusJson);
            case "GET /meta": return new Reply(200, meta());
            case "GET /team": return new Reply(200, team());
            case "GET /me": return new Reply(200, me == null ? Map.of("loggedIn", false) : meJson(me));
            case "POST /login": return login(ex, body(ex));
            case "POST /logout": {
                if (me != null) { sessions.remove(me.token); plugin.db().update("DELETE FROM web_sessions WHERE token = ?", me.token); }
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
                return new Reply(200, Map.of("ok", true));
            }
            case "GET /forum": return new Reply(200, categories());
            case "GET /forum/news": return new Reply(200, news());
            case "POST /forum/thread": return newThread(me, body(ex));
            case "POST /forum/reply": return reply(me, body(ex));
            case "POST /forum/delete": return delete(me, body(ex));
            case "POST /forum/flag": return flag(me, body(ex));
            default: break;
        }
        if (method.equals("GET") && path.startsWith("/forum/c/")) {
            String cat = path.substring(9);
            int page = 0;
            String qs = ex.getRequestURI().getQuery();
            if (qs != null && qs.startsWith("page=")) try { page = Math.max(0, Integer.parseInt(qs.substring(5))); } catch (NumberFormatException ignored) { }
            return threads(cat, page);
        }
        if (method.equals("GET") && path.startsWith("/forum/t/")) {
            try { return thread(Long.parseLong(path.substring(9))); } catch (NumberFormatException e) { return new Reply(404, err("Nicht gefunden")); }
        }
        return new Reply(404, err("Nicht gefunden"));
    }

    private Map<String, Object> meJson(Session s) {
        return Map.of("loggedIn", true, "name", s.name, "rank", s.rank == null ? "default" : s.rank, "moderator", s.mod);
    }

    private Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", cfg.getString("title", "MineSMP"));
        m.put("tagline", cfg.getString("tagline", ""));
        m.put("address", cfg.getString("server-address", "minesmp.de"));
        m.put("version", cfg.getString("version-label", "Java 26.3"));
        m.put("discord", plugin.getConfig().getString("links.discord", ""));
        m.put("store", cfg.getString("store-url", ""));
        m.put("rules", plugin.getConfig().getStringList("rules"));
        List<Map<String, Object>> ranks = new ArrayList<>();
        if (plugin.ranks() != null) for (RankService.Rank r : plugin.ranks().all()) {
            Map<String, Object> rk = new LinkedHashMap<>();
            rk.put("id", r.id());
            rk.put("badge", r.badge());
            rk.put("label", Text.strip(Text.gradients(r.text())).trim());
            rk.put("color", String.format("#%02X%02X%02X", r.c1().getRed(), r.c1().getGreen(), r.c1().getBlue()));
            ranks.add(rk);
        }
        m.put("ranks", ranks);
        m.put("shop", section(cfg.getConfigurationSection("shop")));
        m.put("features", cfg.getMapList("features"));
        m.put("categories", cfg.getMapList("forum.categories"));
        m.put("legal", section(cfg.getConfigurationSection("legal")));
        return m;
    }

    private static Object section(ConfigurationSection s) {
        if (s == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : s.getKeys(false)) {
            Object v = s.get(k);
            out.put(k, v instanceof ConfigurationSection cs ? section(cs) : v);
        }
        return out;
    }

    private Object team() {
        List<String> order = new ArrayList<>();
        Map<String, String> group = new LinkedHashMap<>();
        for (Map<?, ?> g : cfg.getMapList("team")) {
            Object rs = g.get("ranks");
            if (rs instanceof List<?> l) for (Object o : l) { order.add(String.valueOf(o)); group.put(String.valueOf(o), String.valueOf(g.get("name"))); }
        }
        if (order.isEmpty()) return List.of();
        List<Map<String, Object>> rows = q(c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name, rank_id, last_seen FROM web_members ORDER BY name")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String rk = rs.getString(2);
                    if (!order.contains(rk)) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString(1)); m.put("rank", rk); m.put("lastSeen", rs.getLong(3));
                    out.add(m);
                }
            }
            return out;
        });
        Set<String> on = onlineNames;
        for (Map<String, Object> m : rows) m.put("online", on.contains(String.valueOf(m.get("name")).toLowerCase(Locale.ROOT)));
        rows.sort((a, b) -> order.indexOf(String.valueOf(a.get("rank"))) - order.indexOf(String.valueOf(b.get("rank"))));
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map<?, ?> g : cfg.getMapList("team")) {
            List<?> rs = g.get("ranks") instanceof List<?> l ? l : List.of();
            List<Map<String, Object>> members = rows.stream().filter(m -> rs.contains(m.get("rank"))).toList();
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("name", g.get("name")); gm.put("text", g.get("text") == null ? "" : g.get("text")); gm.put("members", members);
            groups.add(gm);
        }
        return groups;
    }

    private Reply login(HttpExchange ex, JsonObject b) {
        if (limited("login:" + ip(ex), 8, TimeUnit.MINUTES.toMillis(10))) return new Reply(429, err("Zu viele Versuche - warte ein paar Minuten."));
        String name = str(b, "name").trim().toLowerCase(Locale.ROOT), code = str(b, "code").trim();
        Code c = codes.get(name);
        if (c == null || c.expires < System.currentTimeMillis()
                || !MessageDigest.isEqual(c.code.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8)))
            return new Reply(401, err("Name oder Code stimmt nicht. Code im Spiel mit /web holen (5 Minuten gültig)."));
        codes.remove(name);
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        long exp = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30);
        Session s = new Session(token, c.uuid.toString(), c.name, c.rank, c.mod, exp);
        plugin.db().update("DELETE FROM web_sessions WHERE expires < ?", System.currentTimeMillis());
        plugin.db().update("INSERT INTO web_sessions (token, uuid, name, rank_id, moderator, expires) VALUES (?,?,?,?,?,?)",
                token, s.uuid, s.name, s.rank, s.mod ? 1 : 0, exp);
        sessions.put(token, s);
        boolean https = "https".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Forwarded-Proto"));
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax" + (https ? "; Secure" : ""));
        return new Reply(200, meJson(s));
    }

    // ---------------- Forum ----------------

    private Map<String, Object> category(String id) {
        for (Map<?, ?> m : cfg.getMapList("forum.categories")) if (id.equals(String.valueOf(m.get("id")))) {
            Map<String, Object> o = new LinkedHashMap<>();
            m.forEach((k, v) -> o.put(String.valueOf(k), v));
            return o;
        }
        return null;
    }

    private Object categories() {
        Map<String, long[]> stats = q(c -> {
            Map<String, long[]> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT category, COUNT(*), SUM(replies) + COUNT(*), MAX(last_post) FROM forum_threads WHERE deleted = 0 GROUP BY category")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) out.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3), rs.getLong(4)});
            }
            return out;
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<?, ?> m : cfg.getMapList("forum.categories")) {
            Map<String, Object> o = new LinkedHashMap<>();
            m.forEach((k, v) -> o.put(String.valueOf(k), v));
            long[] st = stats.getOrDefault(String.valueOf(m.get("id")), new long[]{0, 0, 0});
            o.put("threads", st[0]); o.put("posts", st[1]); o.put("last", st[2]);
            out.add(o);
        }
        return out;
    }

    private Reply threads(String cat, int page) {
        Map<String, Object> c = category(cat);
        if (c == null) return new Reply(404, err("Bereich nicht gefunden"));
        List<Map<String, Object>> list = q(con -> {
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT id, title, author_name, author_rank, created, last_post, replies, pinned, locked FROM forum_threads"
                    + " WHERE category = ? AND deleted = 0 ORDER BY pinned DESC, last_post DESC LIMIT 25 OFFSET ?")) {
                Database.bind(ps, cat, page * 25);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("id", rs.getLong(1)); t.put("title", rs.getString(2)); t.put("author", rs.getString(3)); t.put("rank", rs.getString(4));
                    t.put("created", rs.getLong(5)); t.put("last", rs.getLong(6)); t.put("replies", rs.getInt(7));
                    t.put("pinned", rs.getInt(8) == 1); t.put("locked", rs.getInt(9) == 1);
                    out.add(t);
                }
            }
            return out;
        });
        return new Reply(200, Map.of("category", c, "threads", list, "page", page));
    }

    private Reply thread(long id) {
        Map<String, Object> t = q(con -> {
            try (PreparedStatement ps = con.prepareStatement("SELECT id, category, title, author_name, author_rank, created, replies, pinned, locked FROM forum_threads WHERE id = ? AND deleted = 0")) {
                ps.setLong(1, id);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong(1)); m.put("category", rs.getString(2)); m.put("title", rs.getString(3)); m.put("author", rs.getString(4));
                m.put("rank", rs.getString(5)); m.put("created", rs.getLong(6)); m.put("replies", rs.getInt(7)); m.put("pinned", rs.getInt(8) == 1);
                m.put("locked", rs.getInt(9) == 1);
                List<Map<String, Object>> posts = new ArrayList<>();
                try (PreparedStatement pp = con.prepareStatement("SELECT id, author_name, author_rank, body, created FROM forum_posts WHERE thread_id = ? AND deleted = 0 ORDER BY id")) {
                    pp.setLong(1, id);
                    ResultSet r2 = pp.executeQuery();
                    while (r2.next()) {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("id", r2.getLong(1)); p.put("author", r2.getString(2)); p.put("rank", r2.getString(3)); p.put("body", r2.getString(4)); p.put("created", r2.getLong(5));
                        posts.add(p);
                    }
                }
                m.put("posts", posts);
                return m;
            }
        });
        if (t == null) return new Reply(404, err("Thema nicht gefunden"));
        t.put("categoryInfo", category(String.valueOf(t.get("category"))));
        return new Reply(200, t);
    }

    private Object news() {
        String cat = cfg.getString("forum.news-category", "news");
        return q(con -> {
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT t.id, t.title, t.author_name, t.author_rank, t.created, (SELECT body FROM forum_posts p WHERE p.thread_id = t.id AND p.deleted = 0 ORDER BY p.id LIMIT 1)"
                    + " FROM forum_threads t WHERE t.category = ? AND t.deleted = 0 ORDER BY t.pinned DESC, t.created DESC LIMIT 3")) {
                ps.setString(1, cat);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String body = rs.getString(6) == null ? "" : rs.getString(6);
                    m.put("id", rs.getLong(1)); m.put("title", rs.getString(2)); m.put("author", rs.getString(3)); m.put("rank", rs.getString(4));
                    m.put("created", rs.getLong(5)); m.put("excerpt", body.length() > 220 ? body.substring(0, 220) + " ..." : body);
                    out.add(m);
                }
            }
            return out;
        });
    }

    private Reply newThread(Session me, JsonObject b) {
        if (me == null) return new Reply(401, err("Bitte anmelden (im Spiel /web)."));
        Map<String, Object> c = category(str(b, "category"));
        if (c == null) return new Reply(404, err("Bereich nicht gefunden"));
        if (Boolean.TRUE.equals(c.get("team-only")) && !me.mod) return new Reply(403, err("Hier kann nur das Team neue Themen erstellen."));
        String title = clean(str(b, "title"), 80).replace("\n", " "), body = clean(str(b, "body"), 4000);
        if (title.length() < 3 || body.length() < 2) return new Reply(400, err("Titel (mind. 3 Zeichen) und Text eingeben."));
        if (limited("post:" + me.uuid, 1, 20_000) || limited("day:" + me.uuid, 40, TimeUnit.DAYS.toMillis(1))) return new Reply(429, err("Bitte kurz warten, bevor du wieder schreibst."));
        long now = System.currentTimeMillis();
        long id = q(con -> {
            long tid;
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO forum_threads (category, title, author_uuid, author_name, author_rank, created, last_post) VALUES (?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                Database.bind(ps, c.get("id"), title, me.uuid, me.name, me.rank, now, now);
                ps.executeUpdate();
                tid = Database.insertId(ps);
            }
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO forum_posts (thread_id, author_uuid, author_name, author_rank, body, created) VALUES (?,?,?,?,?,?)")) {
                Database.bind(ps, tid, me.uuid, me.name, me.rank, body, now);
                ps.executeUpdate();
            }
            return tid;
        });
        plugin.audit().log(me.name, "WEB_THREAD", id + " " + title);
        return new Reply(200, Map.of("id", id));
    }

    private Reply reply(Session me, JsonObject b) {
        if (me == null) return new Reply(401, err("Bitte anmelden (im Spiel /web)."));
        long tid;
        try { tid = Long.parseLong(str(b, "thread")); } catch (NumberFormatException e) { return new Reply(400, err("Thema fehlt")); }
        String body = clean(str(b, "body"), 4000);
        if (body.length() < 2) return new Reply(400, err("Text eingeben."));
        if (limited("post:" + me.uuid, 1, 15_000) || limited("day:" + me.uuid, 40, TimeUnit.DAYS.toMillis(1))) return new Reply(429, err("Bitte kurz warten, bevor du wieder schreibst."));
        long now = System.currentTimeMillis();
        String res = q(con -> {
            try (PreparedStatement ps = con.prepareStatement("SELECT locked FROM forum_threads WHERE id = ? AND deleted = 0")) {
                ps.setLong(1, tid);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) return "missing";
                if (rs.getInt(1) == 1 && !me.mod) return "locked";
            }
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO forum_posts (thread_id, author_uuid, author_name, author_rank, body, created) VALUES (?,?,?,?,?,?)")) {
                Database.bind(ps, tid, me.uuid, me.name, me.rank, body, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement("UPDATE forum_threads SET replies = replies + 1, last_post = ? WHERE id = ?")) {
                Database.bind(ps, now, tid);
                ps.executeUpdate();
            }
            return "ok";
        });
        return switch (res) {
            case "missing" -> new Reply(404, err("Thema nicht gefunden"));
            case "locked" -> new Reply(403, err("Dieses Thema ist geschlossen."));
            default -> new Reply(200, Map.of("ok", true));
        };
    }

    private Reply delete(Session me, JsonObject b) {
        if (me == null) return new Reply(401, err("Bitte anmelden."));
        long pid;
        try { pid = Long.parseLong(str(b, "post")); } catch (NumberFormatException e) { return new Reply(400, err("Beitrag fehlt")); }
        String res = q(con -> {
            long tid; String author; long first;
            try (PreparedStatement ps = con.prepareStatement("SELECT thread_id, author_uuid FROM forum_posts WHERE id = ? AND deleted = 0")) {
                ps.setLong(1, pid);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) return "missing";
                tid = rs.getLong(1); author = rs.getString(2);
            }
            if (!me.mod && !author.equals(me.uuid)) return "forbidden";
            try (PreparedStatement ps = con.prepareStatement("SELECT MIN(id) FROM forum_posts WHERE thread_id = ? AND deleted = 0")) {
                ps.setLong(1, tid);
                ResultSet rs = ps.executeQuery();
                first = rs.next() ? rs.getLong(1) : -1;
            }
            try (PreparedStatement ps = con.prepareStatement("UPDATE forum_posts SET deleted = 1 WHERE id = ?")) { ps.setLong(1, pid); ps.executeUpdate(); }
            if (first == pid) {
                try (PreparedStatement ps = con.prepareStatement("UPDATE forum_threads SET deleted = 1 WHERE id = ?")) { ps.setLong(1, tid); ps.executeUpdate(); }
                return "thread";
            }
            try (PreparedStatement ps = con.prepareStatement("UPDATE forum_threads SET replies = CASE WHEN replies > 0 THEN replies - 1 ELSE 0 END WHERE id = ?")) { ps.setLong(1, tid); ps.executeUpdate(); }
            return "post";
        });
        if (res.equals("missing")) return new Reply(404, err("Beitrag nicht gefunden"));
        if (res.equals("forbidden")) return new Reply(403, err("Nur eigene Beiträge oder als Team."));
        plugin.audit().log(me.name, "WEB_DELETE", "Beitrag " + pid);
        return new Reply(200, Map.of("deleted", res));
    }

    private Reply flag(Session me, JsonObject b) {
        if (me == null || !me.mod) return new Reply(403, err("Nur für das Team."));
        long tid;
        try { tid = Long.parseLong(str(b, "thread")); } catch (NumberFormatException e) { return new Reply(400, err("Thema fehlt")); }
        String which = str(b, "flag");
        if (!which.equals("pinned") && !which.equals("locked")) return new Reply(400, err("Unbekannt"));
        plugin.db().update("UPDATE forum_threads SET " + which + " = 1 - " + which + " WHERE id = ?", tid);
        return new Reply(200, Map.of("ok", true));
    }
}
