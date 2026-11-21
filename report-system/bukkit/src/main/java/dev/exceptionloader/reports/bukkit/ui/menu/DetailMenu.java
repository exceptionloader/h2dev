package dev.exceptionloader.reports.bukkit.ui.menu;

import dev.exceptionloader.reports.api.model.Report;
import dev.exceptionloader.reports.bukkit.config.Formats;
import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.flow.ModerationFlow;
import dev.exceptionloader.reports.bukkit.ui.BrowserState;
import fr.minuskube.inv.ClickableItem;
import fr.minuskube.inv.content.InventoryContents;
import fr.minuskube.inv.content.InventoryProvider;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Details of a single report with the moderator actions.
 */
final class DetailMenu implements InventoryProvider {

    static final int ROWS = 3;

    private final MenuKit kit;
    private final MenuReportUi ui;
    private final ModerationFlow moderation;
    private final Formats formats;
    private final Report report;
    private final BrowserState returnTo;

    DetailMenu(MenuKit kit, MenuReportUi ui, ModerationFlow moderation, Formats formats, Report report, BrowserState returnTo) {
        this.kit = kit;
        this.ui = ui;
        this.moderation = moderation;
        this.formats = formats;
        this.report = report;
        this.returnTo = returnTo;
    }

    @Override
    public void init(Player viewer, InventoryContents contents) {
        Messages messages = kit.messages();
        TagResolver placeholders = formats.report(report);

        List<Component> lore = new ArrayList<>(messages.lines("gui.details.info-lore", placeholders));
        if (report.status().isClosed()) {
            lore.addAll(messages.lines("gui.details.info-handled", placeholders));
            if (report.modNote() != null) {
                lore.addAll(messages.lines("gui.details.info-note", placeholders));
            }
        }

        contents.fill(kit.filler());
        contents.set(0, 4, ClickableItem.empty(Items.create(Material.WRITABLE_BOOK, messages.get("gui.details.info", placeholders), lore)));
        contents.set(1, 2, ClickableItem.empty(Items.head(report.reported(), report.reportedName(),
                messages.get("gui.details.reported", placeholders), List.of())));
        contents.set(1, 6, ClickableItem.empty(Items.head(report.reporter(), report.reporterName(),
                messages.get("gui.details.reporter", placeholders), List.of())));
        contents.set(1, 4, kit.button(kit.item(Material.SPYGLASS, "gui.details.stats", "gui.details.stats-lore", placeholders), () -> {
            viewer.closeInventory();
            moderation.showStatistics(viewer, report.reported(), report.reportedName());
        }));

        if (report.isOpen()) {
            contents.set(2, 3, kit.button(kit.item(Material.LIME_CONCRETE, "gui.details.resolve", "gui.details.resolve-lore"), () -> {
                viewer.closeInventory();
                moderation.resolve(viewer, report.id(), () -> ui.showReports(viewer, returnTo));
            }));
            contents.set(2, 5, kit.button(kit.item(Material.RED_CONCRETE, "gui.details.reject", "gui.details.reject-lore"),
                    () -> ui.askForRejectNote(viewer, report, returnTo)));
        }
        contents.set(2, 0, kit.button(kit.item(Material.ARROW, "gui.back"), () -> ui.showReports(viewer, returnTo)));
    }

    @Override
    public void update(Player viewer, InventoryContents contents) {
    }
}
