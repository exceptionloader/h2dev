package de.minesmp.core.utility;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import de.minesmp.core.util.TimeUtil;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.Damageable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Kleine Komfortbefehle: /trash /craft /ec /repair /msg /r /ignore /playtime /discord /rules /help */
public final class MiscCommands {
    private final MineSMP plugin;
    private final Map<UUID, UUID> lastMsg = new HashMap<>();

    public MiscCommands(MineSMP plugin) {
        this.plugin = plugin;
        plugin.command(new SimpleCommand("trash", "minesmp.trash", "Muelleimer", (s, l, a) -> {
            if (s instanceof Player p) p.openInventory(Bukkit.createInventory(null, 36, Text.c("&8Muelleimer &7(wird geloescht)")));
        }, "muell", "disposal"));
        plugin.command(new SimpleCommand("craft", "minesmp.craft", "Werkbank", (s, l, a) -> {
            if (s instanceof Player p) MenuType.CRAFTING.create(p, Text.c("Werkbank")).open();
        }, "workbench", "wb"));
        plugin.command(new SimpleCommand("enderchest", "minesmp.enderchest", "Enderchest", (s, l, a) -> {
            if (!(s instanceof Player p)) return;
            if (plugin.combat().isTagged(p)) { Msg.send(p, "combat.blocked", "{prefix}&cDas kannst du im Kampf nicht tun."); return; }
            p.openInventory(p.getEnderChest());
        }, "ec", "echest"));
        plugin.command(new SimpleCommand("repair", "minesmp.repair", "Item reparieren", this::repair, (s, a) -> a.length == 1 ? List.of("all") : List.of(), "fix"));
        plugin.command(new SimpleCommand("msg", "minesmp.msg", "Private Nachricht", this::msg, "tell", "w", "whisper", "m"));
        plugin.command(new SimpleCommand("reply", "minesmp.msg", "Antworten", this::reply, "r"));
        plugin.command(new SimpleCommand("ignore", "minesmp.ignore", "Spieler ignorieren", this::ignore, "block"));
        plugin.command(new SimpleCommand("playtime", "minesmp.playtime", "Spielzeit", (s, l, a) -> {
            OfflinePlayer t = a.length > 0 ? Bukkit.getOfflinePlayerIfCached(a[0]) : s instanceof Player p ? p : null;
            if (t == null) { Msg.send(s, "player-unknown", "{prefix}&cSpieler &f{name} &cwar noch nie auf MineSMP.", "name", a.length > 0 ? a[0] : "?"); return; }
            long ticks = t.getStatistic(Statistic.PLAY_ONE_MINUTE);
            Msg.send(s, "playtime", "{prefix}&7Spielzeit von &f{name}&7: &b{time}", "name", t.getName(), "time", TimeUtil.format(ticks / 20L * 1000L));
        }, "spielzeit", "pt"));
        plugin.command(new SimpleCommand("discord", "minesmp.info", "Discord-Link", (s, l, a) -> link(s, "discord", "https://discord.gg/minesmp"), "dc"));
        plugin.command(new SimpleCommand("rules", "minesmp.info", "Serverregeln", (s, l, a) -> {
            Msg.send(s, "rules.header", "&8&m------&r &b&lMine&f&lSMP &7Regeln &8&m------");
            List<String> rules = plugin.getConfig().getStringList("rules");
            for (int i = 0; i < rules.size(); i++) s.sendMessage(Text.c("&b" + (i + 1) + ". &f" + rules.get(i)));
        }, "regeln"));
        plugin.command(new SimpleCommand("help", "minesmp.info", "Hilfe", (s, l, a) -> {
            int page = 1;
            try { if (a.length > 0) page = Integer.parseInt(a[0]); } catch (NumberFormatException ignored) { }
            List<String> lines = plugin.getConfig().getStringList("help");
            int per = 10, pages = Math.max(1, (lines.size() + per - 1) / per);
            page = Math.max(1, Math.min(page, pages));
            Msg.send(s, "help.header", "&8&m-----&r &b&lMine&f&lSMP &7Hilfe &8({page}/{pages}) &8&m-----", "page", String.valueOf(page), "pages", String.valueOf(pages));
            for (int i = (page - 1) * per; i < Math.min(lines.size(), page * per); i++) s.sendMessage(Text.c(lines.get(i)));
            if (page < pages) Msg.send(s, "help.next", "&7Naechste Seite: &b/help {n}", "n", String.valueOf(page + 1));
        }, (s, a) -> List.of("1", "2", "3"), "hilfe", "?"));
    }

    private void link(CommandSender s, String key, String def) {
        String url = plugin.getConfig().getString("links." + key, def);
        s.sendMessage(Text.c(Msg.get("link." + key, "{prefix}&7Klicke hier: &b&n{url}", "url", url)).clickEvent(ClickEvent.openUrl(url)));
    }

    private void repair(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        boolean all = a.length > 0 && a[0].equalsIgnoreCase("all");
        if (all && !p.hasPermission("minesmp.repair.all")) { Msg.send(p, "no-permission", "{prefix}&cDazu hast du keine Berechtigung."); return; }
        long cd = plugin.cooldowns().remaining(p.getUniqueId(), "repair");
        if (cd > 0 && !p.hasPermission("minesmp.repair.nocooldown")) { Msg.send(p, "cooldown", "{prefix}&cBitte warte noch &f{time}&c.", "time", TimeUtil.format(cd)); return; }
        int n = 0;
        ItemStack[] items = all ? p.getInventory().getContents() : new ItemStack[]{p.getInventory().getItemInMainHand()};
        double costPer = plugin.getConfig().getDouble("repair.cost-per-item", 0);
        for (ItemStack it : items) {
            if (it == null || !(it.getItemMeta() instanceof Damageable dm) || !dm.hasDamage()) continue;
            if (costPer > 0 && !plugin.eco().withdraw(p, costPer, "REPAIR", it.getType().name())) {
                Msg.send(p, "not-enough-money", "{prefix}&cDu hast nicht genug MineCoins.");
                break;
            }
            it.editMeta(Damageable.class, m -> m.setDamage(0));
            n++;
        }
        if (n == 0) { Msg.send(p, "repair.nothing", "{prefix}&cNichts zu reparieren."); return; }
        plugin.cooldowns().set(p.getUniqueId(), "repair", plugin.getConfig().getLong("cooldowns.repair", 600) * 1000);
        Msg.send(p, "repair.done", "{prefix}&a{n} Item(s) repariert.", "n", String.valueOf(n));
    }

    private void msg(CommandSender s, String l, String[] a) {
        if (a.length < 2) { Msg.send(s, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/msg <Spieler> <Nachricht>"); return; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null || (s instanceof Player sp && !sp.canSee(t))) { Msg.send(s, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a[0]); return; }
        send(s, t, String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
    }

    private void reply(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p) || a.length == 0) return;
        UUID last = lastMsg.get(p.getUniqueId());
        Player t = last == null ? null : Bukkit.getPlayer(last);
        if (t == null) { Msg.send(p, "msg.no-reply", "{prefix}&cNiemand, dem du antworten kannst."); return; }
        send(p, t, String.join(" ", a));
    }

    private void send(CommandSender from, Player to, String message) {
        if (from instanceof Player p) {
            if (plugin.punish().mute(p) != null) { Msg.send(p, "mute.active", "{prefix}&cDu bist stummgeschaltet."); return; }
            PlayerData td = plugin.data().get(to);
            if ((!td.setting("msg", true) || td.ignored.contains(p.getUniqueId().toString())) && !p.hasPermission("minesmp.staff.member")) {
                Msg.send(p, "msg.disabled", "{prefix}&f{name} &cempfaengt keine privaten Nachrichten.", "name", to.getName());
                return;
            }
            if (!plugin.chat().allowMessage(p, message)) return;
            lastMsg.put(p.getUniqueId(), to.getUniqueId());
            lastMsg.put(to.getUniqueId(), p.getUniqueId());
        }
        String clean = from.hasPermission("minesmp.chat.color") ? message : message.replace("&", "");
        Msg.send(from, "msg.to", "&8[&bDu &8» &f{name}&8] &7{msg}", "name", to.getName(), "msg", clean);
        Msg.send(to, "msg.from", "&8[&f{name} &8» &bDir&8] &7{msg}", "name", from.getName(), "msg", clean);
        for (Player spy : Bukkit.getOnlinePlayers()) {
            if (spy != from && spy != to && spy.hasPermission("minesmp.staff.socialspy") && plugin.data().get(spy).setting("socialspy", false)) {
                Msg.send(spy, "msg.spy", "&8[&cSpy&8] &7{from} &8» &7{to}&8: &f{msg}", "from", from.getName(), "to", to.getName(), "msg", clean);
            }
        }
    }

    private void ignore(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        PlayerData d = plugin.data().get(p);
        if (a.length == 0) {
            Msg.send(p, "ignore.list", "{prefix}&7Ignoriert: &f{list}", "list", d.ignored.isEmpty() ? "-" : String.join(", ",
                    d.ignored.stream().map(id -> { OfflinePlayer o = Bukkit.getOfflinePlayer(UUID.fromString(id)); return o.getName() == null ? id : o.getName(); }).toList()));
            return;
        }
        OfflinePlayer t = Bukkit.getPlayerExact(a[0]);
        if (t == null) t = Bukkit.getOfflinePlayerIfCached(a[0]);
        if (t == null || t.getUniqueId().equals(p.getUniqueId())) { Msg.send(p, "player-unknown", "{prefix}&cSpieler &f{name} &cwar noch nie auf MineSMP.", "name", a[0]); return; }
        if (t.isOnline() && t.getPlayer().hasPermission("minesmp.staff.member")) { Msg.send(p, "ignore.staff", "{prefix}&cTeammitglieder koennen nicht ignoriert werden."); return; }
        String id = t.getUniqueId().toString();
        if (d.ignored.remove(id)) Msg.send(p, "ignore.removed", "{prefix}&7Du ignorierst &f{name} &7nicht mehr.", "name", t.getName());
        else { d.ignored.add(id); Msg.send(p, "ignore.added", "{prefix}&7Du ignorierst jetzt &f{name}&7.", "name", t.getName()); }
        d.dirty = true;
    }
}
