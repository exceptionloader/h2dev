package de.minesmp.core.economy;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerDataManager;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.SimpleCommand;
import de.minesmp.core.util.Text;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /money, /pay, /baltop, /eco */
public final class EconomyCommands {
    private final MineSMP plugin;
    private final Map<UUID, PendingPay> pending = new HashMap<>();

    private record PendingPay(UUID target, double amount, long until) {}

    public EconomyCommands(MineSMP plugin) {
        this.plugin = plugin;
        plugin.command(new SimpleCommand("money", "minesmp.money", "Zeigt deinen Kontostand", this::money, "bal", "balance", "geld", "coins"));
        plugin.command(new SimpleCommand("pay", "minesmp.pay", "Ueberweist MineCoins", this::pay, (s, a) -> a.length == 1 ? null : List.of(), "zahlen", "ueberweisen"));
        plugin.command(new SimpleCommand("baltop", "minesmp.baltop", "Reichste Spieler", this::baltop, "moneytop", "topmoney"));
        plugin.command(new SimpleCommand("eco", "minesmp.admin.eco", "Economy-Verwaltung", this::eco,
                (s, a) -> a.length == 1 ? List.of("give", "take", "set") : a.length == 2 ? null : List.of(), "economy"));
    }

    private void money(CommandSender s, String l, String[] a) {
        if (a.length >= 1 && s.hasPermission("minesmp.money.others")) {
            Player t = plugin.getServer().getPlayerExact(a[0]);
            if (t == null) {
                Msg.send(s, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a[0]);
                return;
            }
            Msg.send(s, "money.other", "{prefix}&f{name} &7hat &a{amount}&7.", "name", t.getName(), "amount", plugin.eco().format(plugin.eco().balance(t)));
            return;
        }
        if (!(s instanceof Player p)) return;
        Msg.send(p, "money.self", "{prefix}&7Kontostand: &a{amount}", "amount", plugin.eco().format(plugin.eco().balance(p)));
    }

    private void pay(CommandSender s, String l, String[] a) {
        if (!(s instanceof Player p)) return;
        if (a.length == 1 && a[0].equalsIgnoreCase("confirm")) {
            PendingPay pp = pending.remove(p.getUniqueId());
            if (pp == null || pp.until < System.currentTimeMillis()) {
                Msg.send(p, "pay.no-pending", "{prefix}&cKeine offene Ueberweisung.");
                return;
            }
            Player t = plugin.getServer().getPlayer(pp.target);
            if (t == null) {
                Msg.send(p, "pay.target-offline", "{prefix}&cDer Empfaenger ist nicht mehr online.");
                return;
            }
            doPay(p, t, pp.amount);
            return;
        }
        if (a.length < 2) {
            Msg.send(p, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/pay <Spieler> <Betrag>");
            return;
        }
        Player t = plugin.getServer().getPlayerExact(a[0]);
        if (t == null || !p.canSee(t)) {
            Msg.send(p, "player-not-found", "{prefix}&cSpieler &f{name} &cist nicht online.", "name", a[0]);
            return;
        }
        if (t.equals(p)) {
            Msg.send(p, "pay.self", "{prefix}&cDu kannst dir nicht selbst Geld senden.");
            return;
        }
        double amount = Text.parseAmount(a[1]);
        double min = plugin.getConfig().getDouble("economy.pay-minimum", 1);
        if (amount < min) {
            Msg.send(p, "pay.invalid", "{prefix}&cUngueltiger Betrag. Minimum: &f{min}", "min", plugin.eco().format(min));
            return;
        }
        if (!plugin.data().get(t).setting("pay", true)) {
            Msg.send(p, "pay.disabled", "{prefix}&f{name} &cnimmt keine Zahlungen an.", "name", t.getName());
            return;
        }
        double confirmAbove = plugin.getConfig().getDouble("economy.pay-confirm-above", 100_000);
        if (amount >= confirmAbove) {
            pending.put(p.getUniqueId(), new PendingPay(t.getUniqueId(), amount, System.currentTimeMillis() + 30_000));
            Msg.send(p, "pay.confirm", "{prefix}&eDu willst &a{amount} &ean &f{name} &esenden. Bestaetige mit &b/pay confirm &e(30s).",
                    "amount", plugin.eco().format(amount), "name", t.getName());
            return;
        }
        doPay(p, t, amount);
    }

    private void doPay(Player p, Player t, double amount) {
        if (!plugin.cooldowns().tryUse(p.getUniqueId(), "pay", 1500)) {
            Msg.send(p, "pay.cooldown", "{prefix}&cBitte warte kurz.");
            return;
        }
        if (!plugin.eco().transfer(p, t, amount, "PAY")) {
            Msg.send(p, "not-enough-money", "{prefix}&cDu hast nicht genug MineCoins.");
            return;
        }
        Msg.send(p, "pay.sent", "{prefix}&7Du hast &a{amount} &7an &f{name} &7gesendet.", "amount", plugin.eco().format(amount), "name", t.getName());
        Msg.send(t, "pay.received", "{prefix}&7Du hast &a{amount} &7von &f{name} &7erhalten.", "amount", plugin.eco().format(amount), "name", p.getName());
        if (amount >= plugin.getConfig().getDouble("discord.large-payment", 1_000_000)) {
            plugin.webhook().send("economy", "Grosse Ueberweisung", p.getName() + " -> " + t.getName() + ": " + plugin.eco().format(amount), 0x00BCD4);
        }
    }

    private void baltop(CommandSender s, String l, String[] a) {
        plugin.data().top("balance", 10, list -> {
            Msg.send(s, "baltop.header", "&8&m-----&r &b&lMine&f&lCoins &7Top 10 &8&m-----");
            int i = 1;
            for (PlayerDataManager.TopEntry e : list) {
                Msg.send(s, "baltop.line", "&b#{rank} &f{name} &8- &a{amount}", "rank", String.valueOf(i++), "name", e.name(), "amount", plugin.eco().format(e.value()));
            }
        });
    }

    private void eco(CommandSender s, String l, String[] a) {
        if (a.length < 3) {
            Msg.send(s, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/eco <give|take|set> <Spieler> <Betrag>");
            return;
        }
        double amount = a[0].equalsIgnoreCase("set") && a[2].equals("0") ? 0 : Text.parseAmount(a[2]);
        if (amount < 0) {
            Msg.send(s, "pay.invalid-amount", "{prefix}&cUngueltiger Betrag.");
            return;
        }
        Player t = plugin.getServer().getPlayerExact(a[1]);
        if (t == null) {
            OfflinePlayer op = plugin.getServer().getOfflinePlayerIfCached(a[1]);
            if (op == null || !a[0].equalsIgnoreCase("give")) {
                Msg.send(s, "eco.online-only", "{prefix}&cDer Spieler muss online sein (offline nur &fgive&c).");
                return;
            }
            plugin.eco().depositAny(op.getUniqueId(), amount, "ADMIN", "eco give von " + s.getName());
            plugin.audit().log(s, "ECO_GIVE", op.getName() + " +" + amount);
            Msg.send(s, "eco.done", "{prefix}&aErledigt.");
            return;
        }
        switch (a[0].toLowerCase()) {
            case "give" -> plugin.eco().deposit(t, amount, "ADMIN", "eco give von " + s.getName());
            case "take" -> {
                double take = Math.min(amount, plugin.eco().balance(t));
                if (take > 0) plugin.eco().withdraw(t, take, "ADMIN", "eco take von " + s.getName());
            }
            case "set" -> plugin.eco().set(t, amount);
            default -> {
                Msg.send(s, "usage", "{prefix}&7Benutzung: &b{usage}", "usage", "/eco <give|take|set> <Spieler> <Betrag>");
                return;
            }
        }
        plugin.audit().log(s, "ECO_" + a[0].toUpperCase(), t.getName() + " " + amount);
        Msg.send(s, "eco.result", "{prefix}&aNeuer Kontostand von &f{name}&a: &f{amount}", "name", t.getName(), "amount", plugin.eco().format(plugin.eco().balance(t)));
    }
}
