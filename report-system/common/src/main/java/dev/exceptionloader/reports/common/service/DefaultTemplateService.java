package dev.exceptionloader.reports.common.service;

import dev.exceptionloader.reports.api.TemplateService;
import dev.exceptionloader.reports.api.model.ReportTemplate;
import dev.exceptionloader.reports.common.inject.PluginLogger;
import dev.exceptionloader.reports.common.scheduler.PlatformScheduler;
import dev.exceptionloader.reports.common.storage.TemplateRepository;
import dev.exceptionloader.reports.common.storage.entity.TemplateModel;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.jetbrains.annotations.Blocking;

/**
 * Keeps an immutable snapshot of the templates in memory. A reload builds a new snapshot and
 * swaps the reference, so readers on other threads never need a lock.
 */
@Singleton
public class DefaultTemplateService implements TemplateService {

    private final TemplateRepository repository;
    private final PlatformScheduler scheduler;
    private final Logger logger;

    private volatile Snapshot snapshot = Snapshot.of(DefaultTemplates.templates());

    @Inject
    public DefaultTemplateService(TemplateRepository repository, PlatformScheduler scheduler, @PluginLogger Logger logger) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    @Override
    public List<ReportTemplate> templates() {
        return snapshot.ordered();
    }

    @Override
    public Optional<ReportTemplate> template(String id) {
        return Optional.ofNullable(snapshot.byId().get(id.toLowerCase(Locale.ROOT)));
    }

    @Override
    public CompletableFuture<Void> reload() {
        return CompletableFuture.runAsync(this::load, scheduler.async());
    }

    @Blocking
    private void load() {
        if (repository.isEmpty()) {
            repository.insertMissing(DefaultTemplates.models());
            logger.info("Created the default report templates");
        }

        List<ReportTemplate> loaded = repository.findAll().stream()
                .filter(TemplateModel::isEnabled)
                .filter(model -> model.getId() != null && !model.getId().isBlank())
                .map(TemplateModel::toTemplate)
                .toList();
        if (loaded.isEmpty()) {
            logger.warning("No enabled report templates in the database, keeping the current ones");
            return;
        }

        Snapshot next = Snapshot.of(loaded);
        if (!next.equals(snapshot)) {
            snapshot = next;
            logger.info("Loaded " + next.ordered().size() + " report templates");
        }
    }

    private record Snapshot(List<ReportTemplate> ordered, Map<String, ReportTemplate> byId) {

        static Snapshot of(Collection<ReportTemplate> templates) {
            List<ReportTemplate> ordered = templates.stream()
                    .sorted(Comparator.comparingInt(ReportTemplate::sortOrder).thenComparing(ReportTemplate::id))
                    .toList();
            Map<String, ReportTemplate> byId = new LinkedHashMap<>();
            ordered.forEach(template -> byId.putIfAbsent(template.id(), template));
            return new Snapshot(List.copyOf(byId.values()), Collections.unmodifiableMap(byId));
        }
    }
}
