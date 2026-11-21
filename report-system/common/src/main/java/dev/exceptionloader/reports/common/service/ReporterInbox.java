package dev.exceptionloader.reports.common.service;

import dev.exceptionloader.reports.api.model.Report;
import dev.exceptionloader.reports.common.inject.PluginLogger;
import dev.exceptionloader.reports.common.scheduler.PlatformScheduler;
import dev.exceptionloader.reports.common.storage.ObjectIds;
import dev.exceptionloader.reports.common.storage.ReportRepository;
import dev.exceptionloader.reports.common.storage.entity.ReportModel;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Delivery bookkeeping for report results.
 *
 * <p>A result may only be shown once, but several instances can try to deliver it at the same time
 * (the status broadcast reaches every server, a player may switch servers right then). Delivery is
 * therefore always preceded by an atomic claim in MongoDB; only the instance that wins the claim
 * sends the message.
 */
@Singleton
public class ReporterInbox {

    private static final int MAX_PER_JOIN = 10;

    private final ReportRepository repository;
    private final PlatformScheduler scheduler;
    private final Logger logger;

    @Inject
    public ReporterInbox(ReportRepository repository, PlatformScheduler scheduler, @PluginLogger Logger logger) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    /**
     * @return the report if this caller won the claim
     */
    public CompletableFuture<Optional<Report>> claim(String reportId) {
        return ObjectIds.parse(reportId)
                .map(id -> scheduler.supplyAsync(() -> repository.claimNotification(id).map(ReportModel::toReport)))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()));
    }

    /**
     * Claims undelivered results of a reporter, oldest first. Anything above the per-join limit is
     * left for the next join.
     */
    public CompletableFuture<List<Report>> claimPending(UUID reporter) {
        return scheduler.supplyAsync(() -> {
            List<Report> claimed = new ArrayList<>();
            while (claimed.size() < MAX_PER_JOIN) {
                Optional<ReportModel> next = repository.claimNextPending(reporter);
                if (next.isEmpty()) {
                    break;
                }
                claimed.add(next.get().toReport());
            }
            return claimed;
        });
    }

    /**
     * Returns a claimed result to the inbox so it is delivered on the next join.
     */
    public void release(String reportId) {
        ObjectIds.parse(reportId).ifPresent(id -> scheduler.runAsync(() -> {
            try {
                repository.releaseNotification(id);
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Could not release notification of report " + reportId, exception);
            }
        }));
    }
}
