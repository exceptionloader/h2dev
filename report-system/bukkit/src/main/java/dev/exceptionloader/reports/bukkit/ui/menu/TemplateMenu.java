package dev.exceptionloader.reports.bukkit.ui.menu;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import dev.exceptionloader.reports.api.model.PlayerProfile;
import dev.exceptionloader.reports.api.model.ReportTemplate;
import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.flow.ReportFlow;
import fr.minuskube.inv.ClickableItem;
import fr.minuskube.inv.content.InventoryContents;
import fr.minuskube.inv.content.InventoryProvider;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * One item per template, seven per row, below the head of the reported player.
 */
final class TemplateMenu implements InventoryProvider {

    private static final int PER_ROW = 7;
    private static final int MAX_ROWS = 6;

    private final MenuKit kit;
    private final ReportFlow flow;
    private final PlayerProfile target;
    private final List<ReportTemplate> templates;

    TemplateMenu(MenuKit kit, ReportFlow flow, PlayerProfile target, List<ReportTemplate> templates) {
        this.kit = kit;
        this.flow = flow;
        this.target = target;
        this.templates = List.copyOf(templates.subList(0, Math.min(templates.size(), capacity())));
    }

    static int rowsFor(int templateCount) {
        int templateRows = Math.max(1, (templateCount + PER_ROW - 1) / PER_ROW);
        return Math.min(MAX_ROWS, templateRows + 2);
    }

    private static int capacity() {
        return (MAX_ROWS - 2) * PER_ROW;
    }

    @Override
    public void init(Player viewer, InventoryContents contents) {
        Messages messages = kit.messages();
        TagResolver name = unparsed("name", target.name());
        contents.set(0, 4, ClickableItem.empty(Items.head(target.uuid(), target.name(),
                messages.get("gui.templates.target", name), messages.lines("gui.templates.target-lore", name))));

        for (int index = 0; index < templates.size(); index++) {
            ReportTemplate template = templates.get(index);
            contents.set(1 + index / PER_ROW, 1 + index % PER_ROW,
                    kit.button(templateItem(messages, template), () -> {
                        viewer.closeInventory();
                        flow.chooseTemplate(viewer, target, template);
                    }));
        }

        int lastRow = rowsFor(templates.size()) - 1;
        contents.set(lastRow, 0, kit.button(kit.item(Material.ARROW, "gui.back"), () -> flow.selectTarget(viewer)));
    }

    private static ItemStack templateItem(Messages messages, ReportTemplate template) {
        TagResolver placeholders = TagResolver.resolver(
                unparsed("template", template.name()),
                unparsed("description", template.description()));
        List<Component> lore = new ArrayList<>(messages.lines("gui.templates.template-lore", placeholders));
        if (template.requiresText()) {
            lore.add(messages.get("gui.templates.requires-text"));
        }
        return Items.create(Items.material(template.icon(), Material.PAPER), messages.get("gui.templates.template", placeholders), lore);
    }

    @Override
    public void update(Player viewer, InventoryContents contents) {
    }
}
