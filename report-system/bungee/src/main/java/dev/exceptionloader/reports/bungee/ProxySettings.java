package dev.exceptionloader.reports.bungee;

import dev.exceptionloader.reports.common.config.MongoSettings;
import java.time.Duration;
import net.md_5.bungee.config.Configuration;

/**
 * Typed view of the proxy {@code config.yml}.
 */
public record ProxySettings(
        MongoSettings mongo,
        String permission,
        Duration delay,
        Duration cacheTtl,
        boolean notifyWhenEmpty,
        String message) {

    static ProxySettings from(Configuration config) {
        return new ProxySettings(
                new MongoSettings(
                        config.getString("mongodb.uri", "mongodb://localhost:27017"),
                        config.getString("mongodb.database", "reports")),
                config.getString("reminder.permission", "report.admin"),
                Duration.ofSeconds(Math.max(0, config.getLong("reminder.delay-seconds", 3))),
                Duration.ofSeconds(Math.max(0, config.getLong("reminder.cache-seconds", 15))),
                config.getBoolean("reminder.notify-when-empty", false),
                config.getString("reminder.message", "&8[&cREPORT&8] &7Du hast aktuell &e%count% &7offene Reports. Nutze &e/reports&7."));
    }
}
