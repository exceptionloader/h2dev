package de.minesmp.core.economy;

import de.minesmp.core.MineSMP;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Vault-Schnittstelle, damit andere Plugins (z.B. Shops, Jobs) MineCoins verwenden koennen. */
final class VaultEconomy extends AbstractEconomy {
    private final MineSMP plugin;
    private final EconomyService eco;

    VaultEconomy(MineSMP plugin, EconomyService eco) {
        this.plugin = plugin;
        this.eco = eco;
    }

    @Override public boolean isEnabled() { return plugin.isEnabled(); }
    @Override public String getName() { return "MineSMP"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) { return eco.format(amount); }
    @Override public String currencyNamePlural() { return "MineCoins"; }
    @Override public String currencyNameSingular() { return "MineCoin"; }

    private UUID id(String name) {
        OfflinePlayer p = Bukkit.getPlayerExact(name);
        if (p == null) p = Bukkit.getOfflinePlayerIfCached(name);
        return p == null ? null : p.getUniqueId();
    }

    // ---- OfflinePlayer-Varianten (bevorzugt) ----
    @Override public boolean hasAccount(OfflinePlayer p) { return p.isOnline() || p.hasPlayedBefore(); }
    @Override public boolean hasAccount(OfflinePlayer p, String world) { return hasAccount(p); }
    @Override public double getBalance(OfflinePlayer p) { return Math.max(0, eco.offlineBalanceBlocking(p.getUniqueId())); }
    @Override public double getBalance(OfflinePlayer p, String world) { return getBalance(p); }
    @Override public boolean has(OfflinePlayer p, double amount) { return getBalance(p) + 1e-9 >= amount; }
    @Override public boolean has(OfflinePlayer p, String world, double amount) { return has(p, amount); }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer op, double amount) {
        if (!EconomyService.valid(amount)) return fail(amount, "Ungueltiger Betrag");
        Player p = op.getPlayer();
        if (p != null) {
            return eco.withdraw(p, amount, "VAULT", "Vault-Abbuchung")
                    ? new EconomyResponse(amount, eco.balance(p), EconomyResponse.ResponseType.SUCCESS, null)
                    : fail(amount, "Nicht genug Geld");
        }
        double bal = eco.offlineBalanceBlocking(op.getUniqueId());
        if (bal < amount) return fail(amount, "Nicht genug Geld");
        plugin.data().addBalanceOffline(op.getUniqueId(), -EconomyService.round(amount));
        eco.log(op.getUniqueId(), null, -amount, "VAULT", "Vault-Abbuchung (offline)");
        return new EconomyResponse(amount, bal - amount, EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String world, double amount) { return withdrawPlayer(p, amount); }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer op, double amount) {
        if (!EconomyService.valid(amount)) return fail(amount, "Ungueltiger Betrag");
        eco.depositAny(op.getUniqueId(), amount, "VAULT", "Vault-Gutschrift");
        Player p = op.getPlayer();
        return new EconomyResponse(amount, p != null ? eco.balance(p) : 0, EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String world, double amount) { return depositPlayer(p, amount); }

    // ---- Namensbasierte (veraltete) Varianten ----
    @Override public boolean hasAccount(String name) { return id(name) != null; }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public double getBalance(String name) { UUID u = id(name); return u == null ? 0 : getBalance(Bukkit.getOfflinePlayer(u)); }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public boolean has(String name, double amount) { return getBalance(name) + 1e-9 >= amount; }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public EconomyResponse withdrawPlayer(String name, double amount) { UUID u = id(name); return u == null ? fail(amount, "Unbekannt") : withdrawPlayer(Bukkit.getOfflinePlayer(u), amount); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(String name, double amount) { UUID u = id(name); return u == null ? fail(amount, "Unbekannt") : depositPlayer(Bukkit.getOfflinePlayer(u), amount); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public boolean createPlayerAccount(String name) { return hasAccount(name); }
    @Override public boolean createPlayerAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer p) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String world) { return true; }

    // ---- Banken werden ueber Clans abgebildet, nicht ueber Vault ----
    private EconomyResponse noBank() { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Keine Banken"); }
    private EconomyResponse fail(double amount, String msg) { return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE, msg); }
    @Override public EconomyResponse createBank(String name, String player) { return noBank(); }
    @Override public EconomyResponse deleteBank(String name) { return noBank(); }
    @Override public EconomyResponse bankBalance(String name) { return noBank(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return noBank(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return noBank(); }
    @Override public List<String> getBanks() { return Collections.emptyList(); }
}
