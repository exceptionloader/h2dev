package dev.exceptionloader.reports.bukkit.flow;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component;
import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import dev.exceptionloader.reports.api.ReportService;
import dev.exceptionloader.reports.api.action.DecisionResult;
import dev.exceptionloader.reports.api.action.ReportDecision;
import dev.exceptionloader.reports.api.lookup.LookupResult;
import dev.exceptionloader.reports.api.model.PlayerProfile;
import dev.exceptionloader.reports.api.model.PlayerStatistics;
import dev.exceptionloader.reports.api.model.Report;
import dev.exceptionloader.reports.api.model.ReportStatistics;
import dev.exceptionloader.reports.api.model.ReportStatus;
import dev.exceptionloader.reports.api.model.StatusCounts;
import dev.exceptionloader.reports.api.query.ReportFilter;
import dev.exceptionloader.reports.api.query.ReportQuery;
import dev.exceptionloader.reports.api.query.ReportSort;
import dev.exceptionloader.reports.bukkit.Permissions;
import dev.exceptionloader.reports.bukkit.config.Formats;
import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.scheduler.MainThread;
import dev.exceptionloader.reports.bukkit.ui.BrowserState;
import dev.exceptionloader.reports.bukkit.ui.ReportUiRouter;
import dev.exceptionloader.reports.common.config.ReportSettings;
import dev.exceptionloader.reports.common.text.TextInput;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Moderator actions, shared by commands, menus and forms.
 */
@Singleton
public class ModerationFlow {

    private static final int OPEN_LIST_LIMIT = 10;

    private final ReportService reports;
    private final TargetResolver targets;
    private final ReportUiRouter ui;
    private final Messages messages;
    private final Formats formats;
    private final MainThread main;
    private final ReportSettings settings;

    @Inject
    public ModerationFlow(
            ReportService reports,
            TargetResolver targets,
            ReportUiRouter ui,
            Messages messages,
            Formats formats,
            MainThread main,
            ReportSettings settings) {
        this.reports = reports;
        this.targets = targets;
        this.ui = ui;
        this.messages = messages;
        this.formats = formats;
        this.main = main;
        this.settings = settings;
    }

    public void browse(Player moderator, BrowserState state) {
        ui.of(moderator).showReports(moderator, state);
    }

    /**
     * Text variant of the browser for the console.
     */
    public void listOpen(CommandSender sender) {
        ReportQuery query = new ReportQuery(ReportFilter.openReports(), ReportSort.NEWEST, 0, OPEN_LIST_LIMIT);
        main.handle(reports.find(query), sender, page -> {
            if (page.total() == 0) {
                messages.send(sender, "moderation.open-list-empty");
                return;
            }
            messages.send(sender, "moderation.open-list-header", unparsed("count", String.valueOf(page.total())));
            for (Report report : page.reports()) {
                messages.send(sender, "moderation.open-list-entry", formats.report(report), openReportTag(report));
            }
        });
    }

    public void resolve(CommandSender moderator, String reportId, Runnable onSuccess) {
        decide(moderator, reportId, ReportDecision.resolve(uuidOf(moderator), nameOf(moderator)), onSuccess);
    }

    public void reject(CommandSender moderator, String reportId, String rawNote, Runnable onSuccess) {
        String note = TextInput.clean(rawNote);
        if (note.isEmpty()) {
            messages.send(moderator, "moderation.note-missing");
            return;
        }
        if (note.length() > settings.maxDetailsLength()) {
            messages.send(moderator, "moderation.note-too-long", unparsed("max", String.valueOf(settings.maxDetailsLength())));
            return;
        }
        decide(moderator, reportId, ReportDecision.reject(uuidOf(moderator), nameOf(moderator), note), onSuccess);
    }

    private void decide(CommandSender moderator, String reportId, ReportDecision decision, Runnable onSuccess) {
        main.handle(reports.decide(reportId, decision), moderator, result -> {
            switch (result) {
                case DecisionResult.Applied(Report report) -> {
                    String key = report.status() == ReportStatus.RESOLVED ? "moderation.resolved" : "moderation.rejected";
                    messages.send(moderator, key, formats.report(report));
                    onSuccess.run();
                }
                case DecisionResult.NotFound(String id) -> messages.send(moderator, "moderation.not-found", unparsed("id", id));
                case DecisionResult.AlreadyClosed(Report report) -> messages.send(moderator, "moderation.already-closed", formats.report(report));
                case DecisionResult.OwnReport ownReport -> messages.send(moderator, "moderation.own-report");
            }
        });
    }

    public void showDetails(CommandSender sender, String reportId) {
        main.handle(reports.findById(reportId), sender, report -> report.ifPresentOrElse(
                found -> printDetails(sender, found),
                () -> messages.send(sender, "moderation.not-found", unparsed("id", reportId))));
    }

    private void printDetails(CommandSender sender, Report report) {
        TagResolver placeholders = TagResolver.resolver(formats.report(report), actionTags(report));
        messages.send(sender, "details.body", placeholders);
        if (report.status().isClosed()) {
            messages.send(sender, "details.handled", placeholders);
            if (report.modNote() != null) {
                messages.send(sender, "details.note", placeholders);
            }
        } else if (sender.hasPermission(Permissions.ADMIN)) {
            messages.send(sender, "details.actions", placeholders);
        }
    }

    public void showStatistics(CommandSender sender) {
        main.handle(reports.statistics(), sender, statistics -> printStatistics(sender, statistics));
    }

    public void showStatistics(CommandSender sender, String name) {
        main.handle(targets.resolve(name), sender, result -> {
            switch (result) {
                case LookupResult.Found(PlayerProfile profile) -> showStatistics(sender, profile);
                case LookupResult.NotFound notFound -> messages.send(sender, "report.not-found", unparsed("name", notFound.name()));
                case LookupResult.InvalidName invalid -> messages.send(sender, "report.invalid-name", unparsed("name", invalid.name()));
                case LookupResult.Unavailable unavailable ->
                        messages.send(sender, "report.lookup-unavailable", unparsed("name", unavailable.name()));
            }
        });
    }

    public void showStatistics(CommandSender sender, PlayerProfile player) {
        showStatistics(sender, player.uuid(), player.name());
    }

    public void showStatistics(CommandSender sender, UUID player, String name) {
        main.handle(reports.statistics(player), sender, statistics -> printStatistics(sender, name, statistics));
    }

    private void printStatistics(CommandSender sender, ReportStatistics statistics) {
        List<Component> entries = statistics.topTemplates().entrySet().stream()
                .map(entry -> messages.get("stats.top-entry",
                        unparsed("template", formats.templateName(entry.getKey())),
                        unparsed("count", String.valueOf(entry.getValue()))))
                .toList();
        Component top = entries.isEmpty()
                ? messages.get("stats.top-empty")
                : Component.join(JoinConfiguration.commas(true), entries);
        messages.send(sender, "stats.global", counts("", statistics.counts()), component("top", top));
    }

    private void printStatistics(CommandSender sender, String name, PlayerStatistics statistics) {
        long accuracy = Math.round(statistics.filed().acceptanceRate() * 100);
        messages.send(sender, "stats.player",
                unparsed("name", name),
                counts("received_", statistics.received()),
                counts("filed_", statistics.filed()),
                unparsed("accuracy", String.valueOf(accuracy)));
    }

    private static TagResolver counts(String prefix, StatusCounts counts) {
        return TagResolver.resolver(
                unparsed(prefix + "total", String.valueOf(counts.total())),
                unparsed(prefix + "open", String.valueOf(counts.open())),
                unparsed(prefix + "resolved", String.valueOf(counts.resolved())),
                unparsed(prefix + "rejected", String.valueOf(counts.rejected())));
    }

    private TagResolver actionTags(Report report) {
        return TagResolver.resolver(
                Placeholder.styling("copy_id",
                        ClickEvent.copyToClipboard(report.id()),
                        HoverEvent.showText(messages.get("details.copy-hover"))),
                Placeholder.styling("resolve_button",
                        ClickEvent.runCommand("/report resolve " + report.id()),
                        HoverEvent.showText(messages.get("details.resolve-hover"))),
                Placeholder.styling("reject_button",
                        ClickEvent.suggestCommand("/report reject " + report.id() + " "),
                        HoverEvent.showText(messages.get("details.reject-hover"))));
    }

    private TagResolver openReportTag(Report report) {
        return Placeholder.styling("open_report",
                ClickEvent.runCommand("/report details " + report.id()),
                HoverEvent.showText(messages.get("alert.report-hover", unparsed("server", report.server()))));
    }

    private static UUID uuidOf(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    private String nameOf(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : messages.plain("general.console");
    }
}
