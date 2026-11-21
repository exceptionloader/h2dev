package dev.exceptionloader.reports.common.config;

import java.util.Objects;

/**
 * Connection settings for MongoDB.
 *
 * @param uri      connection string, may contain credentials and replica set options
 * @param database database holding the report collections
 */
public record MongoSettings(String uri, String database) {

    public MongoSettings {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(database, "database");
    }
}
