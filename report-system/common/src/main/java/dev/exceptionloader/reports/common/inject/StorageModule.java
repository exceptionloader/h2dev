package dev.exceptionloader.reports.common.inject;

import com.google.inject.AbstractModule;
import dev.exceptionloader.reports.common.config.MongoSettings;
import java.time.Clock;

/**
 * MongoDB access. This is all the proxy plugin needs.
 */
public final class StorageModule extends AbstractModule {

    private final MongoSettings settings;

    public StorageModule(MongoSettings settings) {
        this.settings = settings;
    }

    @Override
    protected void configure() {
        bind(MongoSettings.class).toInstance(settings);
        bind(Clock.class).toInstance(Clock.systemUTC());
    }
}
