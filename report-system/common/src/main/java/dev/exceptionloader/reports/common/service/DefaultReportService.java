package dev.exceptionloader.reports.common.service;

import dev.exceptionloader.reports.api.ReportService;
import dev.exceptionloader.reports.api.TemplateService;
import dev.exceptionloader.reports.api.action.DecisionResult;
import dev.exceptionloader.reports.api.action.ReportDecision;
import dev.exceptionloader.reports.api.action.ReportRequest;
import dev.exceptionloader.reports.api.action.SubmitResult;
import dev.exceptionloader.reports.api.action.SubmitResult.Violation;
import dev.exceptionloader.reports.api.message.ReportChannels;
import dev.exceptionloader.reports.api.message.ReportCreatedMessage;
import dev.exceptionloader.reports.api.message.ReportStatusMessage;
import dev.exceptionloader.reports.api.model.PlayerStatistics;
import dev.exceptionloader.reports.api.model.Report;
import dev.exceptionloader.reports.api.model.ReportStatistics;
import dev.exceptionloader.reports.api.model.ReportStatus;
import dev.exceptionloader.reports.api.model.ReportTemplate;
import dev.exceptionloader.reports.api.query.ReportPage;
import dev.exceptionloader.reports.api.query.ReportQuery;
import dev.exceptionloader.reports.common.config.ReportSettings;
import dev.exceptionloader.reports.common.messaging.MessageBus;
import dev.exceptionloader.reports.common.scheduler.PlatformScheduler;
import dev.exceptionloader.reports.common.storage.ObjectIds;
import dev.exceptionloader.reports.common.storage.ReportFilters;
import dev.exceptionloader.reports.common.storage.ReportRepository;
import dev.exceptionloader.reports.common.storage.entity.ReportModel;
import dev.exceptionloader.reports.common.text.TextInput;
import dev.morphia.query.filters.Filter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.Blocking;

@Singleton
public class DefaultReportService implements ReportService {

    private static final int TOP_TEMPLATES = 5;

    private final ReportRepository repository;
    private final TemplateService templates;
    private final ReportCooldowns cooldowns;
    private final MessageBus bus;
    private final PlatformScheduler scheduler;
    private final ReportSettings settings;
    private final Clock clock;

    @Inject
    public DefaultReportService(
            ReportRepository repository,
            TemplateService templates,
            ReportCooldowns cooldowns,
            MessageBus bus,
            PlatformScheduler scheduler,
            ReportSettings settings,
            Clock clock) {
        this.repository = repository;
        this.templates = templates;
        this.cooldowns = cooldowns;
        this.bus = bus;
        this.scheduler = scheduler;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public CompletableFuture<SubmitResult> submit(ReportRequest request) {
        if (request.reporter().uuid().equals(request.reported().uuid())) {
            return invalid(Violation.SELF_REPORT);
        }
        Optional<ReportTemplate> found = templates.template(request.templateId());
        if (found.isEmpty()) {
            return invalid(Violation.UNKNOWN_TEMPLATE);
        }
        ReportTemplate template = found.get();

        String reason = template.name();
        if (template.requiresText()) {
            String details = TextInput.clean(request.details());
            Optional<Violation> violation = checkDetails(details);
            if (violation.isPresent()) {
                return invalid(violation.get());
            }
            reason = details;
        }

        String storedReason = reason;
        return scheduler.supplyAsync(() -> store(request, template, storedReason));
    }

    @Blocking
    private SubmitResult store(ReportRequest request, ReportTemplate template, String reason) {
        UUID reporter = request.reporter().uuid();
        Optional<ReportModel> existing = repository.findOpen(reporter, request.reported().uuid());
        if (existing.isPresent()) {
            return new SubmitResult.AlreadyReported(existing.get().toReport());
        }
        int limit = settings.maxOpenPerReporter();
        if (limit > 0 && repository.countOpenBy(reporter) >= limit) {
            return new SubmitResult.TooManyOpen(limit);
        }
        Optional<Duration> cooldown = cooldowns.tryAcquire(reporter, settings.cooldown());
        if (cooldown.isPresent()) {
            return new SubmitResult.Cooldown(cooldown.get());
        }

        ReportModel model = new ReportModel();
        model.setReporter(reporter);
        model.setReporterName(request.reporter().name());
        model.setReported(request.reported().uuid());
        model.setReportedName(request.reported().name());
        model.setTemplate(template.id());
        model.setReason(reason);
        model.setStatus(ReportStatus.OPEN);
        model.setServer(settings.serverName());
        model.setCreatedAt(now());
        try {
            repository.insert(model);
        } catch (RuntimeException exception) {
            cooldowns.release(reporter);
            throw exception;
        }

        Report report = model.toReport();
        bus.publish(ReportChannels.NEW_REPORT, new ReportCreatedMessage(
                report.id(), report.reporterName(), report.reportedName(), report.reason(), report.server(), report.createdAt()));
        return new SubmitResult.Created(report);
    }

    @Override
    public CompletableFuture<DecisionResult> decide(String reportId, ReportDecision decision) {
        Optional<ObjectId> id = ObjectIds.parse(reportId);
        if (id.isEmpty()) {
            return CompletableFuture.completedFuture(new DecisionResult.NotFound(reportId));
        }
        return scheduler.supplyAsync(() -> {
            Instant handledAt = now();
            Optional<ReportModel> closed = repository.close(id.get(), decision, handledAt);
            if (closed.isPresent()) {
                Report report = closed.get().toReport();
                bus.publish(ReportChannels.STATUS_UPDATE, new ReportStatusMessage(
                        report.id(),
                        report.reporter(),
                        report.reporterName(),
                        report.reportedName(),
                        report.status(),
                        decision.moderatorName(),
                        handledAt));
                return new DecisionResult.Applied(report);
            }
            // the conditional update failed: find out why
            return repository.findById(id.get())
                    .map(ReportModel::toReport)
                    .<DecisionResult>map(report -> report.isOpen()
                            ? new DecisionResult.OwnReport(report)
                            : new DecisionResult.AlreadyClosed(report))
                    .orElseGet(() -> new DecisionResult.NotFound(reportId));
        });
    }

    @Override
    public CompletableFuture<Optional<Report>> findById(String reportId) {
        Optional<ObjectId> id = ObjectIds.parse(reportId);
        if (id.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return scheduler.supplyAsync(() -> repository.findById(id.get()).map(ReportModel::toReport));
    }

    @Override
    public CompletableFuture<ReportPage> find(ReportQuery query) {
        return scheduler.supplyAsync(() -> {
            Filter[] filters = ReportFilters.toFilters(query.filter(), clock.instant());
            long total = repository.count(filters);
            if (total == 0) {
                return new ReportPage(List.of(), 0, query.pageSize(), 0);
            }
            // reports may have been closed since the page was requested
            int lastPage = (int) ((total - 1) / query.pageSize());
            ReportQuery effective = query.page() > lastPage ? query.withPage(lastPage) : query;
            List<Report> reports = repository
                    .find(filters, ReportFilters.toSort(query.sort()), effective.offset(), effective.pageSize())
                    .stream()
                    .map(ReportModel::toReport)
                    .toList();
            return new ReportPage(reports, effective.page(), effective.pageSize(), total);
        });
    }

    @Override
    public CompletableFuture<Long> countOpen() {
        return scheduler.supplyAsync(() -> repository.countByStatus(ReportStatus.OPEN));
    }

    @Override
    public CompletableFuture<ReportStatistics> statistics() {
        return scheduler.supplyAsync(() -> repository.statistics(TOP_TEMPLATES));
    }

    @Override
    public CompletableFuture<PlayerStatistics> statistics(UUID player) {
        return scheduler.supplyAsync(() -> repository.statistics(player));
    }

    @Override
    public CompletableFuture<List<String>> knownServers() {
        return scheduler.supplyAsync(() -> repository.distinctServers().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.naturalOrder())
                .toList());
    }

    private Optional<Violation> checkDetails(String details) {
        if (details.isEmpty()) {
            return Optional.of(Violation.MISSING_DETAILS);
        }
        if (details.length() < settings.minDetailsLength()) {
            return Optional.of(Violation.DETAILS_TOO_SHORT);
        }
        if (details.length() > settings.maxDetailsLength()) {
            return Optional.of(Violation.DETAILS_TOO_LONG);
        }
        return Optional.empty();
    }

    // MongoDB stores milliseconds, keep the in-memory copy identical to what is persisted
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static CompletableFuture<SubmitResult> invalid(Violation violation) {
        return CompletableFuture.completedFuture(new SubmitResult.Invalid(violation));
    }
}
