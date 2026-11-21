package dev.exceptionloader.reports.bungee;

import com.google.inject.Guice;
import com.google.inject.Injector;
import dev.exceptionloader.reports.common.inject.StorageModule;
import dev.exceptionloader.reports.common.storage.MongoConnector;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.logging.Level;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

/**
 * Proxy side of the report system. Each proxy only reminds its own staff; there is no state shared
 * between proxies apart from MongoDB.
 */
public final class ReportProxyPlugin extends Plugin {

    private MongoConnector mongo;

    @Override
    public void onEnable() {
        try {
            ProxySettings settings = ProxySettings.from(loadConfig());
            Injector injector = Guice.createInjector(new StorageModule(settings.mongo()), new ProxyModule(this, settings));
            mongo = injector.getInstance(MongoConnector.class);
            getProxy().getPluginManager().registerListener(this, injector.getInstance(LoginReminderListener.class));
        } catch (IOException | RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Could not start the report reminder", exception);
        }
    }

    @Override
    public void onDisable() {
        if (mongo != null) {
            mongo.close();
            mongo = null;
        }
    }

    private Configuration loadConfig() throws IOException {
        File folder = getDataFolder();
        File file = new File(folder, "config.yml");
        if (!file.exists()) {
            Files.createDirectories(folder.toPath());
            try (InputStream defaults = getResourceAsStream("config.yml")) {
                Files.copy(defaults, file.toPath());
            }
        }
        return ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
    }
}
