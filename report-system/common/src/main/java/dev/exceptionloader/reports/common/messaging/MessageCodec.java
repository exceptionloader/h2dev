package dev.exceptionloader.reports.common.messaging;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.time.Instant;

/**
 * JSON encoding of Pub/Sub payloads.
 */
public final class MessageCodec {

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantTypeAdapter().nullSafe())
            .disableHtmlEscaping()
            .create();

    public String encode(Object message) {
        return gson.toJson(message);
    }

    public <T> T decode(String json, Class<T> type) {
        return gson.fromJson(json, type);
    }
}
