package de.minesmp.core.admin;

import de.minesmp.core.MineSMP;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/** Sendet Ereignisse asynchron an Discord-Webhooks (config.yml -> discord.webhooks). */
public final class Webhook {
    private final MineSMP plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Webhook(MineSMP plugin) {
        this.plugin = plugin;
    }

    /** channel: z.B. "punishments", "reports", "events", "economy", "server". */
    public void send(String channel, String title, String description, int color) {
        String url = plugin.getConfig().getString("discord.webhooks." + channel, "");
        if (url == null || url.isBlank() || !url.startsWith("https://")) return;
        String json = "{\"username\":\"MineSMP\",\"embeds\":[{\"title\":\"" + esc(title) + "\",\"description\":\"" + esc(description)
                + "\",\"color\":" + color + ",\"timestamp\":\"" + Instant.now() + "\",\"footer\":{\"text\":\"minesmp.de\"}}]}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally(err -> {
            plugin.getLogger().fine("Webhook-Fehler: " + err.getMessage());
            return null;
        });
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : s.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "").toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }
                case '\t' -> sb.append(' ');
                default -> {
                    if (ch < 0x20) continue;
                    sb.append(ch);
                }
            }
        }
        return sb.length() > 1900 ? sb.substring(0, 1900) : sb.toString();
    }
}
