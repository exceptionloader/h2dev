package dev.exceptionloader.reports.common.inject;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import dev.exceptionloader.reports.api.ProfileService;
import dev.exceptionloader.reports.api.ReportService;
import dev.exceptionloader.reports.api.TemplateService;
import dev.exceptionloader.reports.common.config.LookupSettings;
import dev.exceptionloader.reports.common.config.RedisSettings;
import dev.exceptionloader.reports.common.config.ReportSettings;
import dev.exceptionloader.reports.common.lookup.DefaultProfileService;
import dev.exceptionloader.reports.common.messaging.MessageBus;
import dev.exceptionloader.reports.common.messaging.MessageCodec;
import dev.exceptionloader.reports.common.messaging.RedisMessageBus;
import dev.exceptionloader.reports.common.service.DefaultReportService;
import dev.exceptionloader.reports.common.service.DefaultTemplateService;

/**
 * Report services and Redis messaging, used by the game servers. Requires {@link StorageModule}
 * and platform bindings for {@code PlatformScheduler}, {@code BedrockPrefixProvider} and the
 * {@link PluginLogger}.
 */
public final class ServiceModule extends AbstractModule {

    private final RedisSettings redis;
    private final ReportSettings reports;
    private final LookupSettings lookup;

    public ServiceModule(RedisSettings redis, ReportSettings reports, LookupSettings lookup) {
        this.redis = redis;
        this.reports = reports;
        this.lookup = lookup;
    }

    @Override
    protected void configure() {
        bind(RedisSettings.class).toInstance(redis);
        bind(ReportSettings.class).toInstance(reports);
        bind(LookupSettings.class).toInstance(lookup);

        bind(MessageCodec.class).in(Scopes.SINGLETON);
        bind(MessageBus.class).to(RedisMessageBus.class);
        bind(ReportService.class).to(DefaultReportService.class);
        bind(TemplateService.class).to(DefaultTemplateService.class);
        bind(ProfileService.class).to(DefaultProfileService.class);
    }
}
