package dev.exceptionloader.reports.bukkit.ui.form;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component;
import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import dev.exceptionloader.reports.api.ReportService;
import dev.exceptionloader.reports.api.TemplateService;
import dev.exceptionloader.reports.api.model.PlayerProfile;
import dev.exceptionloader.reports.api.model.Report;
import dev.exceptionloader.reports.api.model.ReportStatus;
import dev.exceptionloader.reports.api.model.ReportTemplate;
import dev.exceptionloader.reports.api.query.ReportFilter;
import dev.exceptionloader.reports.api.query.ReportPage;
import dev.exceptionloader.reports.api.query.ReportSort;
import dev.exceptionloader.reports.api.query.TimeRange;
import dev.exceptionloader.reports.bukkit.config.Formats;
import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.config.PluginSettings;
import dev.exceptionloader.reports.bukkit.flow.ModerationFlow;
import dev.exceptionloader.reports.bukkit.flow.ReportFlow;
import dev.exceptionloader.reports.bukkit.flow.TargetResolver;
import dev.exceptionloader.reports.bukkit.scheduler.MainThread;
import dev.exceptionloader.reports.bukkit.ui.BrowserState;
import dev.exceptionloader.reports.bukkit.ui.ReportUi;
import dev.exceptionloader.reports.common.inject.PluginLogger;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.form.util.FormBuilder;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Bedrock edition screens built from Floodgate forms. Only instantiated when Floodgate is present.
 *
 * <p>Simple forms map button indexes to actions; the action list is built together with the
 * buttons so both always line up. Form callbacks may arrive on a network thread and are moved to
 * the main thread before anything else happens.
 */
@Singleton
public class FormReportUi implements ReportUi {

    private static final int PAGE_SIZE = 10;

    private final Provider<ReportFlow> reportFlow;
    private final Provider<ModerationFlow> moderation;
    private final TargetResolver targets;
    private final TemplateService templates;
    private final ReportService reports;
    private final Messages messages;
    private final Formats formats;
    private final MainThread main;
    private final String headUrl;
    private final Logger logger;

    @Inject
    public FormReportUi(
            Provider<ReportFlow> reportFlow,
            Provider<ModerationFlow> moderation,
            TargetResolver targets,
            TemplateService templates,
            ReportService reports,
            Messages messages,
            Formats formats,
            MainThread main,
            PluginSettings settings,
            @PluginLogger Logger logger) {
        this.reportFlow = reportFlow;
        this.moderation = moderation;
        this.targets = targets;
        this.templates = templates;
        this.reports = reports;
        this.messages = messages;
        this.formats = formats;
        this.main = main;
        this.headUrl = settings.bedrockHeadUrl();
        this.logger = logger;
    }

    @Override
    public void showTargets(Player viewer) {
        List<Player> others = viewer.getServer().getOnlinePlayers().stream()
                .filter(other -> !other.equals(viewer) && viewer.canSee(other))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .map(Player.class::cast)
                .toList();

        Buttons buttons = new Buttons(SimpleForm.builder()
                .title(messages.legacy("bedrock.targets-title"))
                .content(messages.legacy(others.isEmpty() ? "bedrock.targets-empty" : "bedrock.targets-content")));
        for (Player other : others) {
            PlayerProfile profile = targets.profileOf(other);
            buttons.add(other.getName(), headOf(other.getUniqueId()), () -> reportFlow.get().selectTemplate(viewer, profile));
        }
        buttons.add(messages.legacy("bedrock.enter-name"), () -> askForName(viewer));
        send(viewer, buttons.build());
    }

    @Override
    public void askForName(Player viewer) {
        send(viewer, CustomForm.builder()
                .title(messages.legacy("bedrock.name-title"))
                .input(messages.legacy("bedrock.name-input"), messages.legacy("bedrock.name-placeholder"))
                .validResultHandler(response -> {
                    String name = response.asInput(0);
                    main.run(() -> {
                        if (name != null && !name.isBlank()) {
                            reportFlow.get().selectTarget(viewer, name);
                        }
                    });
                }));
    }

    @Override
    public void showTemplates(Player viewer, PlayerProfile target) {
        TagResolver name = unparsed("name", target.name());
        Buttons buttons = new Buttons(SimpleForm.builder()
                .title(messages.legacy("bedrock.templates-title", name))
                .content(messages.legacy("bedrock.templates-content", name)));
        for (ReportTemplate template : templates.templates()) {
            buttons.add(messages.legacy("bedrock.template-button", templatePlaceholders(template)),
                    () -> reportFlow.get().chooseTemplate(viewer, target, template));
        }
        send(viewer, buttons.build());
    }

    @Override
    public void askForDetails(Player viewer, PlayerProfile target, ReportTemplate template) {
        // the description goes into the input label: a separate label component would shift the response indexes
        String label = messages.legacy("bedrock.details-input") + "\n" + messages.legacy("bedrock.details-label", templatePlaceholders(template));
        send(viewer, CustomForm.builder()
                .title(messages.legacy("bedrock.details-title", unparsed("name", target.name())))
                .input(label, messages.legacy("bedrock.details-placeholder"))
                .validResultHandler(response -> {
                    String details = response.asInput(0);
                    main.run(() -> reportFlow.get().submit(viewer, target, template, details));
                }));
    }

    @Override
    public void showReports(Player viewer, BrowserState state) {
        reports.find(state.query(PAGE_SIZE))
                .thenCombine(reports.knownServers(), Listing::new)
                .whenCompleteAsync((listing, error) -> {
                    if (error != null) {
                        logger.warning("Could not load reports for " + viewer.getName() + ": " + error.getMessage());
                        messages.send(viewer, "general.error");
                        return;
                    }
                    if (viewer.isOnline()) {
                        sendReports(viewer, state.withPage(listing.page().page()), listing);
                    }
                }, main.executor());
    }

    private void sendReports(Player viewer, BrowserState state, Listing listing) {
        ReportPage page = listing.page();
        TagResolver placeholders = TagResolver.resolver(
                filterPlaceholders(state),
                unparsed("total", String.valueOf(page.total())),
                unparsed("page", String.valueOf(page.page() + 1)),
                unparsed("pages", String.valueOf(page.pageCount())));

        Buttons buttons = new Buttons(SimpleForm.builder()
                .title(messages.legacy("bedrock.reports-title", placeholders))
                .content(messages.legacyLines("bedrock.reports-content", placeholders)));
        for (Report report : page.reports()) {
            buttons.add(messages.legacy("bedrock.report-button", formats.report(report)), () -> showReport(viewer, report, state));
        }
        buttons.add(messages.legacy("bedrock.filter"), () -> showFilter(viewer, state, listing.servers()));
        if (page.hasPrevious()) {
            buttons.add(messages.legacy("bedrock.previous"), () -> showReports(viewer, state.withPage(page.page() - 1)));
        }
        if (page.hasNext()) {
            buttons.add(messages.legacy("bedrock.next"), () -> showReports(viewer, state.withPage(page.page() + 1)));
        }
        buttons.add(messages.legacy("bedrock.close"), () -> {
        });
        send(viewer, buttons.build());
    }

    private void showFilter(Player viewer, BrowserState state, List<String> servers) {
        ReportFilter filter = state.filter();
        List<ReportStatus> statuses = Arrays.asList(null, ReportStatus.OPEN, ReportStatus.RESOLVED, ReportStatus.REJECTED);
        List<String> templateIds = new ArrayList<>();
        templateIds.add(null);
        templates.templates().forEach(template -> templateIds.add(template.id()));
        List<String> serverNames = new ArrayList<>();
        serverNames.add(null);
        serverNames.addAll(servers);
        List<TimeRange> ranges = List.of(TimeRange.values());
        List<ReportSort> sorts = List.of(ReportSort.values());

        send(viewer, CustomForm.builder()
                .title(messages.legacy("bedrock.filter-title"))
                .dropdown(messages.legacy("bedrock.filter-status"),
                        labels(statuses, status -> formats.status(status)), Math.max(0, statuses.indexOf(filter.status())))
                .dropdown(messages.legacy("bedrock.filter-template"),
                        labels(templateIds, this::templateLabel), Math.max(0, templateIds.indexOf(filter.templateId())))
                .dropdown(messages.legacy("bedrock.filter-server"),
                        labels(serverNames, this::serverLabel), Math.max(0, serverNames.indexOf(filter.server())))
                .dropdown(messages.legacy("bedrock.filter-range"),
                        labels(ranges, formats::range), ranges.indexOf(filter.range()))
                .dropdown(messages.legacy("bedrock.filter-sort"),
                        labels(sorts, formats::sort), sorts.indexOf(state.sort()))
                .validResultHandler(response -> {
                    ReportFilter selected = new ReportFilter(
                            statuses.get(response.asDropdown(0)),
                            templateIds.get(response.asDropdown(1)),
                            serverNames.get(response.asDropdown(2)),
                            ranges.get(response.asDropdown(3)));
                    ReportSort sort = sorts.get(response.asDropdown(4));
                    main.run(() -> showReports(viewer, new BrowserState(selected, sort, 0)));
                }));
    }

    @Override
    public void showReport(Player viewer, Report report, BrowserState returnTo) {
        TagResolver placeholders = formats.report(report);
        StringBuilder content = new StringBuilder(messages.legacyLines("bedrock.report-content", placeholders));
        if (report.status().isClosed()) {
            content.append('\n').append(messages.legacyLines("bedrock.report-handled", placeholders));
            if (report.modNote() != null) {
                content.append('\n').append(messages.legacyLines("bedrock.report-note", placeholders));
            }
        }

        Buttons buttons = new Buttons(SimpleForm.builder()
                .title(messages.legacy("bedrock.report-title", placeholders))
                .content(content.toString()));
        if (report.isOpen()) {
            buttons.add(messages.legacy("bedrock.resolve"),
                    () -> moderation.get().resolve(viewer, report.id(), () -> showReports(viewer, returnTo)));
            buttons.add(messages.legacy("bedrock.reject"), () -> askForRejectNote(viewer, report, returnTo));
        }
        buttons.add(messages.legacy("bedrock.stats"),
                () -> moderation.get().showStatistics(viewer, report.reported(), report.reportedName()));
        buttons.add(messages.legacy("bedrock.back"), () -> showReports(viewer, returnTo));
        send(viewer, buttons.build());
    }

    @Override
    public void askForRejectNote(Player viewer, Report report, BrowserState returnTo) {
        send(viewer, CustomForm.builder()
                .title(messages.legacy("bedrock.reject-title"))
                .input(messages.legacy("bedrock.reject-input"), messages.legacy("bedrock.reject-placeholder"))
                .validResultHandler(response -> {
                    String note = response.asInput(0);
                    main.run(() -> moderation.get().reject(viewer, report.id(), note == null ? "" : note,
                            () -> showReports(viewer, returnTo)));
                }));
    }

    private void send(Player viewer, FormBuilder<?, ?, ?> form) {
        if (!FloodgateApi.getInstance().sendForm(viewer.getUniqueId(), form)) {
            logger.warning("Could not send a form to " + viewer.getName());
        }
    }

    private String headOf(UUID uuid) {
        return headUrl.replace("%uuid%", uuid.toString());
    }

    private TagResolver templatePlaceholders(ReportTemplate template) {
        return TagResolver.resolver(unparsed("template", template.name()), unparsed("description", template.description()));
    }

    private TagResolver filterPlaceholders(BrowserState state) {
        ReportFilter filter = state.filter();
        return TagResolver.resolver(
                component("status", formats.status(filter.status())),
                component("template", templateLabel(filter.templateId())),
                component("server", serverLabel(filter.server())),
                component("range", formats.range(filter.range())),
                component("sort", formats.sort(state.sort())));
    }

    private Component templateLabel(String templateId) {
        return templateId == null ? messages.get("labels.all-templates") : Component.text(formats.templateName(templateId));
    }

    private Component serverLabel(String server) {
        return server == null ? messages.get("labels.all-servers") : Component.text(server);
    }

    private static <T> List<String> labels(List<T> options, Function<T, Component> label) {
        return options.stream()
                .map(option -> LegacyComponentSerializer.legacySection().serialize(label.apply(option)))
                .toList();
    }

    private record Listing(ReportPage page, List<String> servers) {
    }

    /**
     * Keeps simple form buttons and their actions in the same order.
     */
    private final class Buttons {

        private final SimpleForm.Builder form;
        private final List<Runnable> actions = new ArrayList<>();

        private Buttons(SimpleForm.Builder form) {
            this.form = form;
        }

        void add(String text, Runnable action) {
            form.button(text);
            actions.add(action);
        }

        void add(String text, String imageUrl, Runnable action) {
            form.button(text, FormImage.Type.URL, imageUrl);
            actions.add(action);
        }

        SimpleForm.Builder build() {
            return form.validResultHandler(response -> {
                int index = response.clickedButtonId();
                if (index >= 0 && index < actions.size()) {
                    main.run(actions.get(index));
                }
            });
        }
    }
}
