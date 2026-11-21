package dev.exceptionloader.reports.bukkit.ui.menu;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component;
import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import dev.exceptionloader.reports.api.ReportService;
import dev.exceptionloader.reports.api.TemplateService;
import dev.exceptionloader.reports.api.model.Report;
import dev.exceptionloader.reports.api.model.ReportStatus;
import dev.exceptionloader.reports.api.model.ReportTemplate;
import dev.exceptionloader.reports.api.query.ReportFilter;
import dev.exceptionloader.reports.api.query.ReportPage;
import dev.exceptionloader.reports.api.query.ReportSort;
import dev.exceptionloader.reports.api.query.TimeRange;
import dev.exceptionloader.reports.bukkit.config.Formats;
import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.ui.BrowserState;
import dev.exceptionloader.reports.bukkit.ui.Cycle;
import fr.minuskube.inv.ClickableItem;
import fr.minuskube.inv.SmartInventory;
import fr.minuskube.inv.content.InventoryContents;
import fr.minuskube.inv.content.InventoryProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Paginated report list with filter controls in the bottom row.
 *
 * <p>Pages are loaded from MongoDB with skip/limit, so only the visible reports are transferred.
 * The menu opens immediately with a loading indicator and fills itself once the data arrives; a
 * ticket counter drops responses of requests that were superseded by a newer click.
 */
final class BrowserMenu implements InventoryProvider {

    static final int ROWS = 6;
    private static final int PAGE_SIZE = 36;
    private static final int CONTROL_ROW = ROWS - 1;

    private final MenuKit kit;
    private final MenuReportUi ui;
    private final ReportService reports;
    private final TemplateService templates;
    private final Formats formats;
    private final Logger logger;
    private final Player viewer;

    private BrowserState state;
    private SmartInventory inventory;
    private InventoryContents contents;
    private ReportPage page;
    private List<String> servers = List.of();
    private boolean failed;
    private boolean initializing;
    private int ticket;

    BrowserMenu(MenuKit kit, MenuReportUi ui, ReportService reports, TemplateService templates, Formats formats,
                Logger logger, Player viewer, BrowserState state) {
        this.kit = kit;
        this.ui = ui;
        this.reports = reports;
        this.templates = templates;
        this.formats = formats;
        this.logger = logger;
        this.viewer = viewer;
        this.state = state;
    }

    void attach(SmartInventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public void init(Player player, InventoryContents contents) {
        this.contents = contents;
        initializing = true;
        try {
            loadServers();
            load();
        } finally {
            initializing = false;
        }
    }

    @Override
    public void update(Player player, InventoryContents contents) {
    }

    private void load() {
        int current = ++ticket;
        page = null;
        failed = false;
        render();
        reports.find(state.query(PAGE_SIZE)).whenCompleteAsync((result, error) -> {
            if (current != ticket || !isOpen()) {
                return;
            }
            if (error != null) {
                failed = true;
                logger.log(Level.WARNING, "Could not load reports for " + viewer.getName(), error);
            } else {
                page = result;
                state = state.withPage(result.page());
            }
            render();
        }, kit.main().executor());
    }

    private void loadServers() {
        reports.knownServers().whenCompleteAsync((result, error) -> {
            if (error == null) {
                servers = result;
                if (isOpen()) {
                    renderControls();
                }
            }
        }, kit.main().executor());
    }

    private boolean isOpen() {
        // SmartInvs registers the inventory as open only after init, rendering into the contents is fine before that
        return initializing || kit.isViewing(viewer, inventory);
    }

    private void render() {
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            contents.set(slot / 9, slot % 9, null);
        }
        if (failed) {
            contents.set(1, 4, ClickableItem.empty(kit.item(Material.BARRIER, "gui.browser.failed")));
        } else if (page == null) {
            contents.set(1, 4, ClickableItem.empty(kit.item(Material.CLOCK, "gui.browser.loading")));
        } else if (page.reports().isEmpty()) {
            contents.set(1, 4, ClickableItem.empty(kit.item(Material.LIME_DYE, "gui.browser.empty")));
        } else {
            List<Report> visible = page.reports();
            for (int index = 0; index < visible.size(); index++) {
                Report report = visible.get(index);
                BrowserState returnTo = state;
                contents.set(index / 9, index % 9, kit.button(reportItem(report), () -> ui.showReport(viewer, report, returnTo)));
            }
        }
        contents.fillRow(CONTROL_ROW - 1, kit.filler());
        renderControls();
    }

    private void renderControls() {
        Messages messages = kit.messages();
        ReportFilter filter = state.filter();
        contents.fillRow(CONTROL_ROW, kit.filler());

        if (page != null && page.hasPrevious()) {
            contents.set(CONTROL_ROW, 0, kit.button(kit.item(Material.ARROW, "gui.previous"), () -> showPage(state.page() - 1)));
        }

        List<ReportStatus> statuses = Arrays.asList(null, ReportStatus.OPEN, ReportStatus.RESOLVED, ReportStatus.REJECTED);
        contents.set(CONTROL_ROW, 1, cycle(Material.REDSTONE_TORCH, "gui.browser.status", statuses, filter.status(),
                formats::status, status -> state = state.withFilter(filter.withStatus(status))));

        List<String> templateIds = new ArrayList<>();
        templateIds.add(null);
        templates.templates().stream().map(ReportTemplate::id).forEach(templateIds::add);
        contents.set(CONTROL_ROW, 2, cycle(Material.BOOK, "gui.browser.template", templateIds, filter.templateId(),
                id -> id == null ? messages.get("labels.all-templates") : Component.text(formats.templateName(id)),
                id -> state = state.withFilter(filter.withTemplate(id))));

        List<String> serverNames = new ArrayList<>();
        serverNames.add(null);
        serverNames.addAll(servers);
        contents.set(CONTROL_ROW, 3, cycle(Material.COMPASS, "gui.browser.server", serverNames, filter.server(),
                server -> server == null ? messages.get("labels.all-servers") : Component.text(server),
                server -> state = state.withFilter(filter.withServer(server))));

        long total = page == null ? 0 : page.total();
        TagResolver info = TagResolver.resolver(
                unparsed("total", String.valueOf(total)),
                unparsed("page", String.valueOf(state.page() + 1)),
                unparsed("pages", String.valueOf(page == null ? 1 : page.pageCount())));
        contents.set(CONTROL_ROW, 4, kit.button(kit.item(Material.SUNFLOWER, "gui.browser.info", "gui.browser.info-lore", info), () -> {
            loadServers();
            load();
        }));

        contents.set(CONTROL_ROW, 5, cycle(Material.CLOCK, "gui.browser.range", List.of(TimeRange.values()), filter.range(),
                formats::range, range -> state = state.withFilter(filter.withRange(range))));
        contents.set(CONTROL_ROW, 6, cycle(Material.HOPPER, "gui.browser.sort", List.of(ReportSort.values()), state.sort(),
                formats::sort, sort -> state = state.withSort(sort)));
        contents.set(CONTROL_ROW, 7, kit.button(kit.item(Material.BARRIER, "gui.browser.reset"), () -> {
            state = BrowserState.initial();
            load();
        }));

        if (page != null && page.hasNext()) {
            contents.set(CONTROL_ROW, 8, kit.button(kit.item(Material.ARROW, "gui.next"), () -> showPage(state.page() + 1)));
        }
    }

    private void showPage(int index) {
        state = state.withPage(index);
        load();
    }

    private <T> ClickableItem cycle(Material material, String titleKey, List<T> options, T current,
                                    Function<T, Component> label, Consumer<T> apply) {
        Messages messages = kit.messages();
        List<Component> lore = new ArrayList<>(options.size() + 3);
        for (T option : options) {
            String key = Objects.equals(option, current) ? "gui.browser.option-selected" : "gui.browser.option";
            lore.add(messages.get(key, component("option", label.apply(option))));
        }
        lore.addAll(messages.lines("gui.browser.cycle-hint"));
        ItemStack item = Items.create(material, messages.get(titleKey), lore);
        return kit.button(item, forward -> {
            apply.accept(Cycle.step(options, current, forward));
            load();
        });
    }

    private ItemStack reportItem(Report report) {
        Messages messages = kit.messages();
        TagResolver placeholders = formats.report(report);
        Material icon = templates.template(report.templateId())
                .map(template -> Items.material(template.icon(), Material.PAPER))
                .orElse(Material.PAPER);
        return Items.create(icon, messages.get("gui.browser.report", placeholders), messages.lines("gui.browser.report-lore", placeholders));
    }
}
