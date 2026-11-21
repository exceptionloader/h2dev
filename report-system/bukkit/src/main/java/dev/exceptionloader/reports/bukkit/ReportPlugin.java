package dev.exceptionloader.reports.bukkit;

import com.google.inject.Guice;
import com.google.inject.Injector;
import dev.exceptionloader.reports.bukkit.config.PluginSettings;
import dev.exceptionloader.reports.bukkit.inject.BukkitModule;
import dev.exceptionloader.reports.common.inject.ServiceModule;
import dev.exceptionloader.reports.common.inject.StorageModule;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

public final class ReportPlugin extends JavaPlugin {

    private PluginLifecycle lifecycle;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            PluginSettings settings = PluginSettings.from(getConfig(), getServer().getPort());
            boolean floodgate = getServer().getPluginManager().getPlugin("floodgate") != null;
            Injector injector = Guice.createInjector(
                    new StorageModule(settings.mongo()),
                    new ServiceModule(settings.redis(), settings.reports(), settings.lookup()),
                    new BukkitModule(this, settings, floodgate));
            lifecycle = injector.getInstance(PluginLifecycle.class);
            lifecycle.start();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Could not start the report system", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (lifecycle != null) {
            lifecycle.stop();
            lifecycle = null;
        }
    }
}
