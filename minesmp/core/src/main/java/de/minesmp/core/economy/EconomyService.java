package de.minesmp.core.economy;

import de.minesmp.core.MineSMP;
import de.minesmp.core.data.PlayerData;
import de.minesmp.core.util.Text;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Waehrung "MineCoins". Alle Geldbewegungen laufen hier durch und werden protokolliert.
 * Betraege werden auf 2 Nachkommastellen abgerundet; negative/NaN-Werte werden abgelehnt.
 */
public final class EconomyService {
    private final MineSMP plugin;
    private final double maxBalance;

    public EconomyService(MineSMP plugin) {
        this.plugin = plugin;
        this.maxBalance = plugin.getConfig().getDouble("economy.max-balance", 1_000_000_000_000D);
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            Bukkit.getServicesManager().register(Economy.class, new VaultEconomy(plugin, this), plugin, ServicePriority.Highest);
            plugin.getLogger().info("Vault-Economy-Provider (MineCoins) registriert.");
        } else {
            plugin.getLogger().warning("Vault nicht gefunden - andere Plugins koennen MineCoins nicht nutzen.");
        }
    }

    public static boolean valid(double amount) {
        return amount > 0 && !Double.isNaN(amount) && !Double.isInfinite(amount);
    }

    public static double round(double amount) {
        return Math.floor(amount * 100 + 1e-6) / 100.0;
    }

    public String currency() {
        return plugin.getConfig().getString("economy.currency-symbol", "MC");
    }

    public String format(double v) {
        return Text.money(v) + " " + currency();
    }

    public double balance(Player p) {
        return plugin.data().get(p).balance;
    }

    public boolean has(Player p, double amount) {
        return plugin.data().get(p).balance + 1e-9 >= amount;
    }

    public boolean withdraw(Player p, double amount, String type, String info) {
        if (!valid(amount)) return false;
        amount = round(amount);
        PlayerData d = plugin.data().get(p);
        if (d.balance + 1e-9 < amount) return false;
        d.balance = round(d.balance - amount);
        d.dirty = true;
        log(p.getUniqueId(), null, -amount, type, info);
        return true;
    }

    public boolean deposit(Player p, double amount, String type, String info) {
        if (!valid(amount)) return false;
        amount = round(amount);
        PlayerData d = plugin.data().get(p);
        d.balance = Math.min(maxBalance, round(d.balance + amount));
        d.dirty = true;
        log(null, p.getUniqueId(), amount, type, info);
        if (plugin.quests() != null && !type.equals("PAY") && !type.equals("ADMIN")) plugin.quests().progress(p, "EARN", null, (int) amount);
        return true;
    }

    /** Gutschrift auch fuer Offline-Spieler. */
    public void depositAny(UUID id, double amount, String type, String info) {
        if (!valid(amount)) return;
        amount = round(amount);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            deposit(p, amount, type, info);
            return;
        }
        plugin.data().addBalanceOffline(id, amount);
        log(null, id, amount, type, info);
    }

    public boolean transfer(Player from, Player to, double amount, String type) {
        if (!valid(amount) || from.equals(to)) return false;
        amount = round(amount);
        PlayerData a = plugin.data().get(from), b = plugin.data().get(to);
        if (a.balance + 1e-9 < amount) return false;
        a.balance = round(a.balance - amount);
        b.balance = Math.min(maxBalance, round(b.balance + amount));
        a.dirty = b.dirty = true;
        log(from.getUniqueId(), to.getUniqueId(), amount, type, from.getName() + " -> " + to.getName());
        return true;
    }

    public void set(Player p, double amount) {
        PlayerData d = plugin.data().get(p);
        d.balance = Math.max(0, Math.min(maxBalance, round(amount)));
        d.dirty = true;
        log(null, p.getUniqueId(), amount, "SET", "Admin");
    }

    public void log(UUID from, UUID to, double amount, String type, String info) {
        if (!plugin.getConfig().getBoolean("economy.log-transactions", true)) return;
        plugin.db().update("INSERT INTO transactions (time, source, target, amount, type, info) VALUES (?, ?, ?, ?, ?, ?)",
                System.currentTimeMillis(), from == null ? null : from.toString(), to == null ? null : to.toString(), amount, type,
                info == null ? null : (info.length() > 250 ? info.substring(0, 250) : info));
    }

    /** Blockierender Offline-Kontostand (nur fuer Vault-Kompatibilitaet). */
    double offlineBalanceBlocking(UUID id) {
        PlayerData d = plugin.data().getIfLoaded(id);
        if (d != null) return d.balance;
        try {
            return plugin.db().query(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM players WHERE uuid = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getDouble(1) : -1.0;
                    }
                }
            }).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return -1;
        }
    }
}
