package de.minesmp.core.combat;

import de.minesmp.core.MineSMP;
import de.minesmp.core.gui.ConfirmMenu;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Items;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Kopfgelder (/bounty): Geld wird beim Setzen abgezogen und beim Kill ausgezahlt. */
public final class BountyService {
    private final MineSMP plugin;
    private final Map<UUID, Double> bounties = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    public BountyService(MineSMP plugin) {
        this.plugin = plugin;
        try {
            String raw = plugin.db().kvGet("bounties").get(10, TimeUnit.SECONDS);
            if (raw != null) {
                YamlConfiguration y = new YamlConfiguration();
                y.loadFromString(raw);
                for (String k : y.getKeys(false)) {
                    UUID u = UUID.fromString(k);
                    bounties.put(u, y.getDouble(k + ".amount"));
                    names.put(u, y.getString(k + ".name", "?"));
                }
            }
        } catch (InvalidConfigurationException | RuntimeException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | InterruptedException e) {
            plugin.getLogger().warning("Kopfgelder konnten nicht geladen werden: " + e.getMessage());
        }
        plugin.command(new SimpleCommand("bounty", "minesmp.bounty", "Kopfgelder", this::cmd,
                (s, a) -> a.length == 1 ? List.of("set", "list") : a.length == 2 ? null : List.of(), "kopfgeld", "bounties"));
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        bounties.forEach((k, v) -> {
            y.set(k + ".amount", v);
            y.set(k + ".name", names.getOrDefault(k, "?"));
        });
        plugin.db().kvSet("bounties", y.saveToString());
    }

    public double get(UUID id) {
        return bounties.getOrDefault(id, 0.0);
    }

    private void cmd(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        if (a.length == 0 || a[0].equalsIgnoreCase("list")) { openList(p); return; }
        if (a[0].equalsIgnoreCase("set") && a.length >= 3) {
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) { Msg.send(p, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a[1]); return; }
            if (t.equals(p)) { Msg.send(p, "bounty.self", "{prefix}&cDu kannst kein Kopfgeld auf dich setzen."); return; }
            double amount = Text.parseAmount(a[2]);
            double min = plugin.getConfig().getDouble("bounty.min", 500);
            if (amount < min) { Msg.send(p, "bounty.min", "{prefix}&cMindestbetrag: &f{min}", "min", plugin.eco().format(min)); return; }
            ConfirmMenu.open(p, "&8Kopfgeld setzen?", Items.head(t, "&c" + t.getName(), List.of()), List.of("&7Betrag: &a" + plugin.eco().format(amount)), () -> {
                if (!plugin.eco().withdraw(p, amount, "BOUNTY_SET", t.getName())) { Msg.send(p, "not-enough-money", "{prefix}&cDu hast nicht genug MineCoins."); return; }
                bounties.merge(t.getUniqueId(), amount, Double::sum);
                names.put(t.getUniqueId(), t.getName());
                save();
                Msg.broadcast("bounty.set", "{prefix}&f{setter} &7hat &a{amount} &7Kopfgeld auf &c{target} &7gesetzt! &8(Gesamt: &a{total}&8)",
                        "setter", p.getName(), "amount", plugin.eco().format(amount), "target", t.getName(), "total", plugin.eco().format(get(t.getUniqueId())));
            });
            return;
        }
        Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/bounty [list] | /bounty set <Spieler> <Betrag>");
    }

    private void openList(Player p) {
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(bounties.entrySet());
        list.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        Menu m = new Menu(6, "&8Kopfgelder") {};
        int i = 0;
        for (var e : list) {
            if (i >= 54) break;
            m.set(i++, Items.head(Bukkit.getOfflinePlayer(e.getKey()), "&c" + names.getOrDefault(e.getKey(), "?"),
                    List.of("&7Kopfgeld: &a" + plugin.eco().format(e.getValue()), Bukkit.getPlayer(e.getKey()) != null ? "&aOnline" : "&7Offline")));
        }
        if (list.isEmpty()) m.set(22, Items.of(Material.BARRIER, "&7Keine Kopfgelder aktiv"));
        m.open(p);
    }

    /** Zahlt das Kopfgeld an den Killer aus (Missbrauchsschutz: gleicher Clan/IP zaehlt nicht). */
    public void claim(Player killer, Player victim) {
        Double amount = bounties.get(victim.getUniqueId());
        if (amount == null || amount <= 0) return;
        if (plugin.clans().sameOrAllied(killer.getUniqueId(), victim.getUniqueId())) return;
        if (killer.getAddress() != null && victim.getAddress() != null
                && killer.getAddress().getAddress().equals(victim.getAddress().getAddress())) return;
        bounties.remove(victim.getUniqueId());
        names.remove(victim.getUniqueId());
        save();
        plugin.eco().deposit(killer, amount, "BOUNTY_CLAIM", victim.getName());
        Msg.broadcast("bounty.claimed", "{prefix}&f{killer} &7hat das Kopfgeld von &a{amount} &7auf &c{victim} &7kassiert!",
                "killer", killer.getName(), "amount", plugin.eco().format(amount), "victim", victim.getName());
    }
}
