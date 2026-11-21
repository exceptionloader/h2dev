package dev.exceptionloader.reports.bungee;

import com.google.inject.AbstractModule;
import dev.exceptionloader.reports.common.inject.PluginLogger;
import dev.exceptionloader.reports.common.scheduler.PlatformScheduler;
import java.util.logging.Logger;
import net.md_5.bungee.api.plugin.Plugin;

final class ProxyModule extends AbstractModule {

    private final ReportProxyPlugin plugin;
    private final ProxySettings settings;

    ProxyModule(ReportProxyPlugin plugin, ProxySettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override
    protected void configure() {
        bind(Plugin.class).toInstance(plugin);
        bind(ProxySettings.class).toInstance(settings);
        bind(Logger.class).annotatedWith(PluginLogger.class).toInstance(plugin.getLogger());
        bind(PlatformScheduler.class).to(BungeePlatformScheduler.class);
    }
}
