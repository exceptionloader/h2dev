package de.minesmp.core.display;

import de.minesmp.core.MineSMP;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/** PlaceholderAPI-Erweiterung: %minesmp_balance%, %minesmp_clan%, %minesmp_level%, ... */
public final class PlaceholderHook extends PlaceholderExpansion {
    private final MineSMP plugin;

    public PlaceholderHook(MineSMP plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "minesmp"; }
    @Override public @NotNull String getAuthor() { return "MineSMP"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || !player.isOnline() || player.getPlayer() == null) return "";
        return plugin.placeholders().value(player.getPlayer(), params);
    }
}
