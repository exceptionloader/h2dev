package de.minesmp.core.utility;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Spieler-Warps (/pwarp): eigene oeffentliche Teleportpunkte, z.B. fuer Shops oder Farmen. */
public final class PlayerWarpService {
    private static final class Warp {
        String name, loc, ownerName, description = "";
        UUID owner;
        int visits;
        Material icon = Material.ENDER_PEARL;
    }

    private final MineSMP plugin;
    private final Map<String, Warp> warps = new LinkedHashMap<>();

    public PlayerWarpService(MineSMP plugin) {
        this.plugin = plugin;
        try {
            String raw = plugin.db().kvGet("pwarps").get(10, TimeUnit.SECONDS);
            if (raw != null) {
                YamlConfiguration y = new YamlConfiguration();
                y.loadFromString(raw);
                for (String k : y.getKeys(false)) {
                    Warp w = new Warp();
                    w.name = k;
                    w.loc = y.getString(k + ".loc");
                    w.owner = UUID.fromString(y.getString(k + ".owner"));
                    w.ownerName = y.getString(k + ".owner-name");
                    w.visits = y.getInt(k + ".visits");
                    w.description = y.getString(k + ".description", "");
                    Material m = Material.matchMaterial(y.getString(k + ".icon", "ENDER_PEARL"));
                    if (m != null) w.icon = m;
                    warps.put(k, w);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Spieler-Warps konnten nicht geladen werden: " + e.getMessage());
        }
        plugin.command(new SimpleCommand("pwarp", "minesmp.pwarp", "Spieler-Warps", this::cmd, (s, a) -> {
            if (a.length == 1) { List<String> l = new ArrayList<>(List.of("set", "delete", "list", "desc", "icon")); l.addAll(warps.keySet()); return l; }
            return a.length == 2 ? new ArrayList<>(warps.keySet()) : List.of();
        }, "pw", "playerwarp"));
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Warp w : warps.values()) {
            y.set(w.name + ".loc", w.loc);
            y.set(w.name + ".owner", w.owner.toString());
            y.set(w.name + ".owner-name", w.ownerName);
            y.set(w.name + ".visits", w.visits);
            y.set(w.name + ".description", w.description);
            y.set(w.name + ".icon", w.icon.name());
        }
        plugin.db().kvSet("pwarps", y.saveToString());
    }

    private int max(Player p) {
        int m = plugin.getConfig().getInt("pwarps.default-max", 1);
        for (int i = 20; i > m; i--) if (p.hasPermission("minesmp.pwarp.limit." + i)) return i;
        return m;
    }

    private void cmd(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        if (a.length == 0 || a[0].equalsIgnoreCase("list")) { open(p, 0); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set" -> {
                if (a.length < 2 || !a[1].matches("[A-Za-z0-9_]{3,16}")) { Msg.send(p, "pwarp.bad-name", "{prefix}&cName: 3-16 Zeichen (A-Z, 0-9, _)."); return; }
                String name = a[1].toLowerCase(Locale.ROOT);
                if (!plugin.worlds().homesAllowed(p.getWorld())) { Msg.send(p, "home.world", "{prefix}&cIn dieser Welt kannst du keine Homes setzen."); return; }
                Warp ex = warps.get(name);
                if (ex != null && !ex.owner.equals(p.getUniqueId())) { Msg.send(p, "pwarp.taken", "{prefix}&cDieser Warp-Name ist vergeben."); return; }
                if (ex == null) {
                    long own = warps.values().stream().filter(w -> w.owner.equals(p.getUniqueId())).count();
                    if (own >= max(p)) { Msg.send(p, "pwarp.limit", "{prefix}&cDu kannst maximal &f{max} &cWarps besitzen.", "max", String.valueOf(max(p))); return; }
                    double cost = plugin.getConfig().getDouble("pwarps.cost", 5000);
                    if (cost > 0 && !plugin.eco().withdraw(p, cost, "PWARP", name)) { Msg.send(p, "pwarp.cost", "{prefix}&cEin Warp kostet &f{cost}&c.", "cost", plugin.eco().format(cost)); return; }
                    ex = new Warp();
                    ex.name = name;
                    ex.owner = p.getUniqueId();
                    warps.put(name, ex);
                }
                ex.ownerName = p.getName();
                ex.loc = PlayerData.locToString(p.getLocation());
                save();
                Msg.send(p, "pwarp.set", "{prefix}&aSpieler-Warp &f{name} &agesetzt.", "name", name);
            }
            case "delete", "del" -> {
                Warp w = a.length > 1 ? warps.get(a[1].toLowerCase(Locale.ROOT)) : null;
                if (w == null || (!w.owner.equals(p.getUniqueId()) && !p.hasPermission("minesmp.pwarp.admin"))) { Msg.send(p, "pwarp.unknown", "{prefix}&cWarp nicht gefunden."); return; }
                warps.remove(w.name);
                save();
                Msg.send(p, "pwarp.deleted", "{prefix}&7Warp &f{name} &7geloescht.", "name", w.name);
            }
            case "desc" -> {
                Warp w = a.length > 2 ? warps.get(a[1].toLowerCase(Locale.ROOT)) : null;
                if (w == null || !w.owner.equals(p.getUniqueId())) { Msg.send(p, "pwarp.unknown", "{prefix}&cWarp nicht gefunden."); return; }
                String d = String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)).replace("&", "");
                w.description = d.length() > 60 ? d.substring(0, 60) : d;
                save();
                Msg.send(p, "pwarp.desc", "{prefix}&aBeschreibung gesetzt.");
            }
            case "icon" -> {
                Warp w = a.length > 1 ? warps.get(a[1].toLowerCase(Locale.ROOT)) : null;
                if (w == null || !w.owner.equals(p.getUniqueId())) { Msg.send(p, "pwarp.unknown", "{prefix}&cWarp nicht gefunden."); return; }
                Material m = p.getInventory().getItemInMainHand().getType();
                if (m.isAir()) return;
                w.icon = m;
                save();
                Msg.send(p, "pwarp.icon", "{prefix}&aSymbol gesetzt.");
            }
            default -> {
                Warp w = warps.get(sub);
                if (w == null) { Msg.send(p, "pwarp.unknown", "{prefix}&cWarp nicht gefunden."); return; }
                visit(p, w);
            }
        }
    }

    private void visit(Player p, Warp w) {
        Location loc = PlayerData.locFromString(w.loc);
        if (loc == null) { Msg.send(p, "pwarp.unknown", "{prefix}&cWarp nicht gefunden."); return; }
        if (!w.owner.equals(p.getUniqueId())) { w.visits++; save(); }
        plugin.teleport().teleport(p, loc, "Warp " + w.name);
    }

    private void open(Player p, int page) {
        List<Warp> list = new ArrayList<>(warps.values());
        list.sort(Comparator.comparingInt((Warp w) -> w.visits).reversed());
        Menu m = new Menu(6, "&8Spieler-Warps &7(" + (page + 1) + ")") {};
        for (int i = 0; i < 45 && page * 45 + i < list.size(); i++) {
            Warp w = list.get(page * 45 + i);
            m.set(i, Items.of(w.icon, 1, "&b" + w.name, List.of("&7Von: &f" + w.ownerName, "&7Besuche: &f" + w.visits,
                    w.description.isEmpty() ? "" : "&f" + w.description, "", "&bKlicke zum Teleportieren")), e -> { Menu.close(p); visit(p, w); });
        }
        if (page > 0) m.set(45, Items.of(Material.SPECTRAL_ARROW, "&bVorherige Seite"), e -> open(p, page - 1));
        if ((page + 1) * 45 < list.size()) m.set(53, Items.of(Material.SPECTRAL_ARROW, "&bNaechste Seite"), e -> open(p, page + 1));
        m.set(49, Items.of(Material.OAK_SIGN, 1, "&7Eigenen Warp erstellen", List.of("&f/pwarp set <Name>", "&7Kosten: &a" + plugin.eco().format(plugin.getConfig().getDouble("pwarps.cost", 5000)))));
        m.open(p);
    }
}
