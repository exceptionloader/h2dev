package dev.exceptionloader.reports.api;

import dev.exceptionloader.reports.api.action.DecisionResult;
import dev.exceptionloader.reports.api.action.ReportDecision;
import dev.exceptionloader.reports.api.action.ReportRequest;
import dev.exceptionloader.reports.api.action.SubmitResult;
import dev.exceptionloader.reports.api.model.PlayerStatistics;
import dev.exceptionloader.reports.api.model.Report;
import dev.exceptionloader.reports.api.model.ReportStatistics;
import dev.exceptionloader.reports.api.query.ReportPage;
import dev.exceptionloader.reports.api.query.ReportQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;

/**
 * Creates, queries and closes reports.
 *
 * <p>Every method is non-blocking. The returned futures complete on a scheduler thread of the
 * platform, never on the server main thread.
 */
public interface ReportService {

    /**
     * Validates and stores a report, then announces it on {@code reports:new}.
     *
     * @param request the report to file
     * @return the outcome; the future only fails on storage errors
     */
    @NotNull CompletableFuture<SubmitResult> submit(@NotNull ReportRequest request);

    /**
     * Closes an open report. Concurrent decisions on the same report are safe: exactly one of them
     * returns {@link DecisionResult.Applied}, the others get {@link DecisionResult.AlreadyClosed}.
     *
     * @param reportId report id as hex string
     * @param decision the decision
     * @return the outcome
     */
    @NotNull CompletableFuture<DecisionResult> decide(@NotNull String reportId, @NotNull ReportDecision decision);

    /**
     * @param reportId report id as hex string
     * @return the report, empty if the id is unknown or malformed
     */
    @NotNull CompletableFuture<Optional<Report>> findById(@NotNull String reportId);

    /**
     * @param query filter, sort and page
     * @return the requested page
     */
    @NotNull CompletableFuture<ReportPage> find(@NotNull ReportQuery query);

    /**
     * @return number of open reports in the whole network
     */
    @NotNull CompletableFuture<Long> countOpen();

    /**
     * @return network wide statistics
     */
    @NotNull CompletableFuture<ReportStatistics> statistics();

    /**
     * @param player player to inspect
     * @return reports received and filed by the player
     */
    @NotNull CompletableFuture<PlayerStatistics> statistics(@NotNull UUID player);

    /**
     * @return names of all server instances that have reports, sorted alphabetically
     */
    @NotNull CompletableFuture<List<String>> knownServers();
}
