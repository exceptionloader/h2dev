package dev.exceptionloader.reports.bukkit.flow;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import dev.exceptionloader.reports.api.ReportService;
import dev.exceptionloader.reports.api.action.ReportRequest;
import dev.exceptionloader.reports.api.action.SubmitResult;
import dev.exceptionloader.reports.api.action.SubmitResult.Violation;
import dev.exceptionloader.reports.api.lookup.LookupResult;
import dev.exceptionloader.reports.api.model.PlayerProfile;
import dev.exceptionloader.reports.api.model.ReportTemplate;
import dev.exceptionloader.reports.bukkit.config.Formats;
import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.scheduler.MainThread;
import dev.exceptionloader.reports.bukkit.ui.ReportUiRouter;
import dev.exceptionloader.reports.common.config.ReportSettings;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Steps of filing a report: pick a player, pick a template, optionally describe the incident,
 * submit. The screens differ per edition, the steps do not.
 */
@Singleton
public class ReportFlow {

    private final ReportService reports;
    private final TargetResolver targets;
    private final ReportUiRouter ui;
    private final Messages messages;
    private final MainThread main;
    private final ReportSettings settings;

    @Inject
    public ReportFlow(
            ReportService reports,
            TargetResolver targets,
            ReportUiRouter ui,
            Messages messages,
            MainThread main,
            ReportSettings settings) {
        this.reports = reports;
        this.targets = targets;
        this.ui = ui;
        this.messages = messages;
        this.main = main;
        this.settings = settings;
    }

    public void selectTarget(Player reporter) {
        ui.of(reporter).showTargets(reporter);
    }

    public void selectTarget(Player reporter, String name) {
        CompletableFuture<LookupResult> lookup = targets.resolve(name);
        if (!lookup.isDone()) {
            messages.send(reporter, "report.looking-up", unparsed("name", name));
        }
        main.handle(lookup, reporter, result -> {
            if (!reporter.isOnline()) {
                return;
            }
            switch (result) {
                case LookupResult.Found(PlayerProfile profile) -> selectTemplate(reporter, profile);
                case LookupResult.NotFound notFound -> messages.send(reporter, "report.not-found", unparsed("name", notFound.name()));
                case LookupResult.InvalidName invalid -> messages.send(reporter, "report.invalid-name", unparsed("name", invalid.name()));
                case LookupResult.Unavailable unavailable ->
                        messages.send(reporter, "report.lookup-unavailable", unparsed("name", unavailable.name()));
            }
        });
    }

    public void selectTemplate(Player reporter, PlayerProfile target) {
        if (target.uuid().equals(reporter.getUniqueId())) {
            messages.send(reporter, "report.self");
            return;
        }
        ui.of(reporter).showTemplates(reporter, target);
    }

    public void chooseTemplate(Player reporter, PlayerProfile target, ReportTemplate template) {
        if (template.requiresText()) {
            ui.of(reporter).askForDetails(reporter, target, template);
        } else {
            submit(reporter, target, template, null);
        }
    }

    public void submit(Player reporter, PlayerProfile target, ReportTemplate template, @Nullable String details) {
        ReportRequest request = new ReportRequest(targets.profileOf(reporter), target, template.id(), details);
        TagResolver placeholders = TagResolver.resolver(
                unparsed("reported", target.name()),
                unparsed("min", String.valueOf(settings.minDetailsLength())),
                unparsed("max", String.valueOf(settings.maxDetailsLength())));

        main.handle(reports.submit(request), reporter, result -> {
            switch (result) {
                case SubmitResult.Created created -> messages.send(reporter, "report.created", placeholders);
                case SubmitResult.AlreadyReported duplicate -> messages.send(reporter, "report.duplicate", placeholders);
                case SubmitResult.Cooldown(Duration remaining) ->
                        messages.send(reporter, "report.cooldown", unparsed("time", Formats.duration(remaining)));
                case SubmitResult.TooManyOpen(int limit) ->
                        messages.send(reporter, "report.too-many-open", unparsed("limit", String.valueOf(limit)));
                case SubmitResult.Invalid(Violation violation) -> {
                    messages.send(reporter, messageKey(violation), placeholders);
                    if (isDetailsProblem(violation) && reporter.isOnline()) {
                        ui.of(reporter).askForDetails(reporter, target, template);
                    }
                }
            }
        });
    }

    private static String messageKey(Violation violation) {
        return switch (violation) {
            case SELF_REPORT -> "report.self";
            case UNKNOWN_TEMPLATE -> "report.unknown-template";
            case MISSING_DETAILS -> "report.details-missing";
            case DETAILS_TOO_SHORT -> "report.details-too-short";
            case DETAILS_TOO_LONG -> "report.details-too-long";
        };
    }

    private static boolean isDetailsProblem(Violation violation) {
        return switch (violation) {
            case MISSING_DETAILS, DETAILS_TOO_SHORT, DETAILS_TOO_LONG -> true;
            case SELF_REPORT, UNKNOWN_TEMPLATE -> false;
        };
    }
}
