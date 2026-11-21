package dev.exceptionloader.reports.bukkit.ui.menu;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import dev.exceptionloader.reports.api.ReportService;
import dev.exceptionloader.reports.api.TemplateService;
import dev.exceptionloader.reports.api.model.PlayerProfile;
import dev.exceptionloader.reports.api.model.Report;
import dev.exceptionloader.reports.api.model.ReportTemplate;
import dev.exceptionloader.reports.bukkit.config.Formats;
import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.flow.ModerationFlow;
import dev.exceptionloader.reports.bukkit.flow.ReportFlow;
import dev.exceptionloader.reports.bukkit.flow.TargetResolver;
import dev.exceptionloader.reports.bukkit.ui.BrowserState;
import dev.exceptionloader.reports.bukkit.ui.ChatPrompts;
import dev.exceptionloader.reports.bukkit.ui.ReportUi;
import dev.exceptionloader.reports.common.inject.PluginLogger;
import fr.minuskube.inv.SmartInventory;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.entity.Player;

/**
 * Java edition screens: SmartInvs menus, free text through the chat.
 */
@Singleton
public class MenuReportUi implements ReportUi {

    private final MenuKit kit;
    private final Provider<ReportFlow> reportFlow;
    private final Provider<ModerationFlow> moderation;
    private final TargetResolver targets;
    private final TemplateService templates;
    private final ReportService reports;
    private final ChatPrompts prompts;
    private final Messages messages;
    private final Formats formats;
    private final Logger logger;

    @Inject
    public MenuReportUi(
            MenuKit kit,
            Provider<ReportFlow> reportFlow,
            Provider<ModerationFlow> moderation,
            TargetResolver targets,
            TemplateService templates,
            ReportService reports,
            ChatPrompts prompts,
            Messages messages,
            Formats formats,
            @PluginLogger Logger logger) {
        this.kit = kit;
        this.reportFlow = reportFlow;
        this.moderation = moderation;
        this.targets = targets;
        this.templates = templates;
        this.reports = reports;
        this.prompts = prompts;
        this.messages = messages;
        this.formats = formats;
        this.logger = logger;
    }

    @Override
    public void showTargets(Player viewer) {
        TargetMenu menu = new TargetMenu(kit, this, reportFlow.get(), targets);
        kit.create("report-targets", TargetMenu.ROWS, messages.legacy("gui.targets.title"), menu).open(viewer);
    }

    @Override
    public void askForName(Player viewer) {
        prompts.ask(viewer, messages.get("report.name-prompt"), name -> reportFlow.get().selectTarget(viewer, name));
    }

    @Override
    public void showTemplates(Player viewer, PlayerProfile target) {
        List<ReportTemplate> available = templates.templates();
        TemplateMenu menu = new TemplateMenu(kit, reportFlow.get(), target, available);
        String title = messages.legacy("gui.templates.title", unparsed("name", target.name()));
        kit.create("report-templates", TemplateMenu.rowsFor(available.size()), title, menu).open(viewer);
    }

    @Override
    public void askForDetails(Player viewer, PlayerProfile target, ReportTemplate template) {
        prompts.ask(viewer, messages.get("report.details-prompt", unparsed("reported", target.name())),
                text -> reportFlow.get().submit(viewer, target, template, text));
    }

    @Override
    public void showReports(Player viewer, BrowserState state) {
        BrowserMenu menu = new BrowserMenu(kit, this, reports, templates, formats, logger, viewer, state);
        SmartInventory inventory = kit.create("report-browser", BrowserMenu.ROWS, messages.legacy("gui.browser.title"), menu);
        menu.attach(inventory);
        inventory.open(viewer);
    }

    @Override
    public void showReport(Player viewer, Report report, BrowserState returnTo) {
        DetailMenu menu = new DetailMenu(kit, this, moderation.get(), formats, report, returnTo);
        kit.create("report-details", DetailMenu.ROWS, messages.legacy("gui.details.title", formats.report(report)), menu).open(viewer);
    }

    @Override
    public void askForRejectNote(Player viewer, Report report, BrowserState returnTo) {
        prompts.ask(viewer, messages.get("moderation.reject-prompt", formats.report(report)),
                note -> moderation.get().reject(viewer, report.id(), note, () -> showReports(viewer, returnTo)));
    }
}
