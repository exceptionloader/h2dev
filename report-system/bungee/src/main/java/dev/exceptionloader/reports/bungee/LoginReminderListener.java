package dev.exceptionloader.reports.bungee;

import dev.exceptionloader.reports.common.inject.PluginLogger;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

/**
 * Tells staff on login how many reports are waiting. Runs once per network login, not on every
 * server switch.
 */
@Singleton
public class LoginReminderListener implements Listener {

    private final Plugin plugin;
    private final OpenReportCounter counter;
    private final ProxySettings settings;
    private final Logger logger;

    @Inject
    public LoginReminderListener(Plugin plugin, OpenReportCounter counter, ProxySettings settings, @PluginLogger Logger logger) {
        this.plugin = plugin;
        this.counter = counter;
        this.settings = settings;
        this.logger = logger;
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        ProxiedPlayer player = event.getPlayer();
        if (!player.hasPermission(settings.permission())) {
            return;
        }
        counter.count().whenComplete((count, error) -> {
            if (error != null) {
                logger.warning("Could not count open reports for " + player.getName() + ": " + error.getMessage());
                return;
            }
            if (count == 0 && !settings.notifyWhenEmpty()) {
                return;
            }
            String text = ChatColor.translateAlternateColorCodes('&', settings.message().replace("%count%", Long.toString(count)));
            plugin.getProxy().getScheduler().schedule(plugin, () -> {
                if (player.isConnected()) {
                    player.sendMessage(TextComponent.fromLegacy(text));
                }
            }, settings.delay().toMillis(), TimeUnit.MILLISECONDS);
        });
    }
}
