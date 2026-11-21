package dev.exceptionloader.reports.bukkit.config;

import dev.exceptionloader.reports.common.config.LookupSettings;
import dev.exceptionloader.reports.common.config.MongoSettings;
import dev.exceptionloader.reports.common.config.RedisSettings;
import dev.exceptionloader.reports.common.config.ReportSettings;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Typed view of {@code config.yml}.
 */
public record PluginSettings(
        MongoSettings mongo,
        RedisSettings redis,
        ReportSettings reports,
        LookupSettings lookup,
        String bedrockPrefix,
        String bedrockHeadUrl,
        Duration templateRefresh,
        DateTimeFormatter dateFormat) {

    public static PluginSettings from(FileConfiguration config, int serverPort) {
        String serverName = config.getString("server-name", "").strip();
        if (serverName.isEmpty()) {
            serverName = "server-" + serverPort;
        }

        ConfigurationSection mongo = section(config, "mongodb");
        ConfigurationSection redis = section(config, "redis");
        ConfigurationSection reports = section(config, "reports");
        ConfigurationSection lookup = section(config, "lookup");
        ConfigurationSection display = section(config, "display");

        return new PluginSettings(
                new MongoSettings(
                        mongo.getString("uri", "mongodb://localhost:27017"),
                        mongo.getString("database", "reports")),
                new RedisSettings(
                        redis.getString("host", "localhost"),
                        redis.getInt("port", 6379),
                        redis.getString("username", ""),
                        redis.getString("password", ""),
                        redis.getInt("database", 0),
                        redis.getBoolean("ssl", false),
                        Duration.ofMillis(Math.max(100, redis.getLong("timeout-millis", 2000)))),
                new ReportSettings(
                        serverName,
                        Duration.ofSeconds(Math.max(0, reports.getLong("cooldown-seconds", 60))),
                        Math.max(0, reports.getInt("max-open-per-reporter", 5)),
                        Math.max(1, reports.getInt("min-text-length", 5)),
                        Math.max(10, reports.getInt("max-text-length", 200))),
                new LookupSettings(
                        URI.create(lookup.getString("base-url", "https://mc-api.io")),
                        Duration.ofMillis(Math.max(500, lookup.getLong("timeout-millis", 4000))),
                        Duration.ofMinutes(Math.max(1, lookup.getLong("cache-minutes", 30))),
                        Duration.ofSeconds(Math.max(1, lookup.getLong("unknown-name-cache-seconds", 120))),
                        Duration.ofSeconds(Math.max(1, lookup.getLong("error-cooldown-seconds", 60)))),
                lookup.getString("bedrock-prefix", "."),
                display.getString("bedrock-head-url", "https://mc-api.io/render/FACE/%uuid%?size=64"),
                Duration.ofMinutes(Math.max(1, reports.getLong("template-refresh-minutes", 5))),
                DateTimeFormatter.ofPattern(display.getString("date-format", "dd.MM.yyyy HH:mm"), Locale.GERMANY)
                        .withZone(ZoneId.of(display.getString("timezone", "Europe/Berlin"))));
    }

    private static ConfigurationSection section(FileConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        return section != null ? section : new YamlConfiguration();
    }
}
