package dev.exceptionloader.reports.bungee;

import dev.exceptionloader.reports.api.model.ReportStatus;
import dev.exceptionloader.reports.common.scheduler.PlatformScheduler;
import dev.exceptionloader.reports.common.storage.ReportRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Counts open reports with a short cache. When the whole team logs in after a restart, one query
 * answers all of them instead of one query per login.
 */
@Singleton
public class OpenReportCounter {

    private final ReportRepository repository;
    private final PlatformScheduler scheduler;
    private final Clock clock;
    private final Duration ttl;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    @Inject
    public OpenReportCounter(ReportRepository repository, PlatformScheduler scheduler, Clock clock, ProxySettings settings) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.clock = clock;
        this.ttl = settings.cacheTtl();
    }

    public CompletableFuture<Long> count() {
        Instant now = clock.instant();
        Snapshot current = snapshot.get();
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.count();
        }
        CompletableFuture<Long> query = new CompletableFuture<>();
        Snapshot next = new Snapshot(query, now.plus(ttl));
        if (!snapshot.compareAndSet(current, next)) {
            // another login started a query in the meantime
            return snapshot.get().count();
        }
        scheduler.runAsync(() -> {
            try {
                query.complete(repository.countByStatus(ReportStatus.OPEN));
            } catch (RuntimeException exception) {
                snapshot.compareAndSet(next, null);
                query.completeExceptionally(exception);
            }
        });
        return query;
    }

    private record Snapshot(CompletableFuture<Long> count, Instant expiresAt) {
    }
}
