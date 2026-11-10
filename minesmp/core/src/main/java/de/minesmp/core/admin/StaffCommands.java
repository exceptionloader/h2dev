package de.minesmp.core.admin;

import de.minesmp.core.MineSMP;
import de.minesmp.core.gui.Menu;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** /staff, /sc (Teamchat), /vanish, /invsee, /ecsee, /broadcast, /minesmp reload */
public final class StaffCommands implements Listener {
    private final MineSMP plugin;

    public StaffCommands(MineSMP plugin) {
        this.plugin = plugin;
        plugin.listen(this);
        plugin.command(new SimpleCommand("staff", "minesmp.staff.list", "Zeigt das Team", this::staff, "team"));
        plugin.command(new SimpleCommand("sc", "minesmp.staff.chat", "Teamchat", this::staffChat, "teamchat"));
        plugin.command(new SimpleCommand("invsee", "minesmp.staff.invsee", "Inventar ansehen", this::invsee));
        plugin.command(new SimpleCommand("ecsee", "minesmp.staff.invsee", "Enderchest ansehen", this::ecsee));
        plugin.command(new SimpleCommand("broadcast", "minesmp.staff.broadcast", "Rundnachricht", (s, l, a) -> {
            if (a.length == 0) return;
            Msg.broadcast("broadcast", "&8[&b&lMineSMP&8] &f{msg}", "msg", String.join(" ", a));
            plugin.audit().log(s, "BROADCAST", String.join(" ", a));
        }, "bc"));
        plugin.command(new SimpleCommand("minesmp", "minesmp.admin.reload", "MineSMP-Verwaltung", (s, l, a) -> {
            if (a.length > 0 && a[0].equalsIgnoreCase("buildspawn")) {
                if (!s.hasPermission("minesmp.admin.spawn")) { Msg.send(s, "no-permission", "{prefix}&cDazu hast du keine Berechtigung."); return; }
                if (a.length < 2 || !a[1].equalsIgnoreCase("confirm")) {
                    Msg.send(s, "spawn.confirm", "{prefix}&eDer Spawn wird komplett neu gebaut (eigene Aenderungen dort gehen verloren). Bestaetigen: &f/minesmp buildspawn confirm");
                    return;
                }
                plugin.spawn().build(s);
                return;
            }
            if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                Msg.load(plugin);
                plugin.reloadConfig();
                plugin.getConfig().options().copyDefaults(true);
                plugin.shop().reload();
                plugin.crates().reload();
                plugin.kits().reload();
                plugin.events().reload();
                plugin.tokenShops().reload();
                plugin.spawners().reload();
                plugin.enchants().reload();
                plugin.poi().reload();
                plugin.scoreboard().reload();
                plugin.infobar().reload();
                plugin.ranks().reload();
                plugin.audit().log(s, "RELOAD", "Konfiguration neu geladen");
                Msg.send(s, "reload.done", "{prefix}&aNachrichten, Shop, Crates, Kits, Events und Scoreboard neu geladen. &7(Andere Aenderungen: Neustart)");
            } else {
                Msg.send(s, "minesmp.info", "{prefix}&bMineSMPCore &f{version} &7- &f/minesmp reload", "version", plugin.getPluginMeta().getVersion());
            }
        }, (s, a) -> a.length == 1 ? List.of("reload", "buildspawn") : a.length == 2 && a[0].equalsIgnoreCase("buildspawn") ? List.of("confirm") : List.of(), "msmp"));
    }

    private void staff(CommandSender s, String l, String[] a) {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("minesmp.staff.member") && (!isVanished(p) || !(s instanceof Player sp) || plugin.vanish().sees(sp))) {
                names.add(plugin.chat().prefix(p) + p.getName());
            }
        }
        Msg.send(s, "staff.header", "{prefix}&bTeam online &7({count})&8: &f{list}", "count", String.valueOf(names.size()),
                "list", names.isEmpty() ? "&7Gerade niemand - nutze &f/report" : String.join("&7, ", names));
    }

    private void staffChat(CommandSender s, String l, String[] a) {
        if (a.length == 0) return;
        String msg = String.join(" ", a);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("minesmp.staff.chat")) Msg.send(p, "staffchat.format", "&8[&cTeam&8] &b{name}&8: &f{msg}", "name", s.getName(), "msg", msg);
        }
        Bukkit.getConsoleSender().sendMessage("[Teamchat] " + s.getName() + ": " + msg);
    }

    public boolean isVanished(Player p) {
        return plugin.vanish() != null && plugin.vanish().isVanished(p);
    }

    private void invsee(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p) || a.length < 1) return;
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) {
            Msg.send(s, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a[0]);
            return;
        }
        plugin.audit().log(p, "INVSEE", t.getName());
        if (p.hasPermission("minesmp.staff.invsee.modify")) {
            p.openInventory(t.getInventory());
        } else {
            Menu m = new Menu(5, "&8Inventar: " + t.getName()) {};
            var contents = t.getInventory().getContents();
            for (int i = 0; i < Math.min(contents.length, 45); i++) if (contents[i] != null) m.set(i, contents[i].clone());
            m.open(p);
        }
    }

    private void ecsee(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p) || a.length < 1) return;
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) {
            Msg.send(s, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a[0]);
            return;
        }
        plugin.audit().log(p, "ECSEE", t.getName());
        if (p.hasPermission("minesmp.staff.invsee.modify")) {
            p.openInventory(t.getEnderChest());
        } else {
            Menu m = new Menu(3, "&8Enderchest: " + t.getName()) {};
            var contents = t.getEnderChest().getContents();
            for (int i = 0; i < contents.length; i++) if (contents[i] != null) m.set(i, contents[i].clone());
            m.open(p);
        }
    }
}
