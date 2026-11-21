package dev.exceptionloader.reports.bukkit.config;

import dev.exceptionloader.reports.common.inject.PluginLogger;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Texts from {@code messages.yml}. Keys missing in the user's file fall back to the bundled
 * defaults, so new keys added by updates work without editing the file.
 */
@Singleton
public class Messages {

    private static final String FILE_NAME = "messages.yml";

    private final Plugin plugin;
    private final Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private volatile YamlConfiguration messages = new YamlConfiguration();
    private volatile TagResolver prefix = TagResolver.empty();

    @Inject
    public Messages(Plugin plugin, @PluginLogger Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream bundled = plugin.getResource(FILE_NAME)) {
            if (bundled != null) {
                try (Reader reader = new InputStreamReader(bundled, StandardCharsets.UTF_8)) {
                    loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
                }
            }
        } catch (IOException exception) {
            logger.warning("Could not read the bundled messages: " + exception.getMessage());
        }
        messages = loaded;
        prefix = Placeholder.parsed("prefix", loaded.getString("prefix", ""));
    }

    public Component get(String key, TagResolver... resolvers) {
        return parse(raw(key), resolvers);
    }

    public List<Component> lines(String key, TagResolver... resolvers) {
        return rawLines(key).stream().map(line -> parse(line, resolvers)).toList();
    }

    /**
     * Sends a message or every line of a list message. Empty lines are skipped, which allows
     * server owners to disable messages.
     */
    public void send(Audience audience, String key, TagResolver... resolvers) {
        for (String line : rawLines(key)) {
            if (!line.isEmpty()) {
                audience.sendMessage(parse(line, resolvers));
            }
        }
    }

    /**
     * @return the message with section sign colors, for inventory titles and Bedrock forms
     */
    public String legacy(String key, TagResolver... resolvers) {
        return LegacyComponentSerializer.legacySection().serialize(get(key, resolvers));
    }

    public String legacyLines(String key, TagResolver... resolvers) {
        return String.join("\n", lines(key, resolvers).stream()
                .map(LegacyComponentSerializer.legacySection()::serialize)
                .toList());
    }

    public String plain(String key) {
        return raw(key);
    }

    private Component parse(String raw, TagResolver... resolvers) {
        return miniMessage.deserialize(raw, TagResolver.resolver(prefix, TagResolver.resolver(resolvers)));
    }

    private String raw(String key) {
        String value = messages.getString(key);
        if (value == null) {
            logger.warning("Missing message '" + key + "'");
            return key;
        }
        return value;
    }

    private List<String> rawLines(String key) {
        return messages.isList(key) ? messages.getStringList(key) : List.of(raw(key));
    }
}
