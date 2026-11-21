package dev.exceptionloader.reports.bukkit.inject;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import dev.exceptionloader.reports.bukkit.ReportPlugin;
import dev.exceptionloader.reports.bukkit.bedrock.BedrockGateway;
import dev.exceptionloader.reports.bukkit.bedrock.FloodgateGateway;
import dev.exceptionloader.reports.bukkit.bedrock.JavaOnlyGateway;
import dev.exceptionloader.reports.bukkit.config.PluginSettings;
import dev.exceptionloader.reports.bukkit.scheduler.BukkitPlatformScheduler;
import dev.exceptionloader.reports.bukkit.ui.BedrockUi;
import dev.exceptionloader.reports.bukkit.ui.ReportUi;
import dev.exceptionloader.reports.bukkit.ui.form.FormReportUi;
import dev.exceptionloader.reports.bukkit.ui.menu.MenuReportUi;
import dev.exceptionloader.reports.common.inject.PluginLogger;
import dev.exceptionloader.reports.common.lookup.BedrockPrefixProvider;
import dev.exceptionloader.reports.common.scheduler.PlatformScheduler;
import fr.minuskube.inv.InventoryManager;
import jakarta.inject.Singleton;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

public final class BukkitModule extends AbstractModule {

    private final ReportPlugin plugin;
    private final PluginSettings settings;
    private final boolean floodgate;

    public BukkitModule(ReportPlugin plugin, PluginSettings settings, boolean floodgate) {
        this.plugin = plugin;
        this.settings = settings;
        this.floodgate = floodgate;
    }

    @Override
    protected void configure() {
        bind(ReportPlugin.class).toInstance(plugin);
        bind(Plugin.class).toInstance(plugin);
        bind(Server.class).toInstance(plugin.getServer());
        bind(PluginSettings.class).toInstance(settings);
        bind(Logger.class).annotatedWith(PluginLogger.class).toInstance(plugin.getLogger());
        bind(PlatformScheduler.class).to(BukkitPlatformScheduler.class);

        // Floodgate classes must not be touched unless the plugin is installed
        if (floodgate) {
            bind(BedrockGateway.class).to(FloodgateGateway.class);
            bind(ReportUi.class).annotatedWith(BedrockUi.class).to(FormReportUi.class);
        } else {
            bind(BedrockGateway.class).toInstance(new JavaOnlyGateway(settings.bedrockPrefix()));
            bind(ReportUi.class).annotatedWith(BedrockUi.class).to(MenuReportUi.class);
        }
        bind(BedrockPrefixProvider.class).to(BedrockGateway.class);
    }

    @Provides
    @Singleton
    InventoryManager inventoryManager() {
        InventoryManager manager = new InventoryManager(plugin);
        manager.init();
        return manager;
    }
}
