package dev.exceptionloader.reports.bukkit.ui.menu;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import dev.exceptionloader.reports.api.model.PlayerProfile;
import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.flow.ReportFlow;
import dev.exceptionloader.reports.bukkit.flow.TargetResolver;
import fr.minuskube.inv.ClickableItem;
import fr.minuskube.inv.content.InventoryContents;
import fr.minuskube.inv.content.InventoryProvider;
import fr.minuskube.inv.content.Pagination;
import fr.minuskube.inv.content.SlotIterator;
import java.util.Comparator;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Heads of all players the viewer can see, plus a button to type a name for offline players.
 */
final class TargetMenu implements InventoryProvider {

    static final int ROWS = 6;
    private static final int PER_PAGE = 45;

    private final MenuKit kit;
    private final MenuReportUi ui;
    private final ReportFlow flow;
    private final TargetResolver targets;

    TargetMenu(MenuKit kit, MenuReportUi ui, ReportFlow flow, TargetResolver targets) {
        this.kit = kit;
        this.ui = ui;
        this.flow = flow;
        this.targets = targets;
    }

    @Override
    public void init(Player viewer, InventoryContents contents) {
        Messages messages = kit.messages();
        ClickableItem[] heads = viewer.getServer().getOnlinePlayers().stream()
                .filter(other -> !other.equals(viewer) && viewer.canSee(other))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .map(other -> {
                    PlayerProfile profile = targets.profileOf(other);
                    TagResolver name = unparsed("name", other.getName());
                    return kit.button(
                            Items.head(other, messages.get("gui.targets.player", name), messages.lines("gui.targets.player-lore", name)),
                            () -> flow.selectTemplate(viewer, profile));
                })
                .toArray(ClickableItem[]::new);

        Pagination pagination = contents.pagination();
        pagination.setItems(heads);
        pagination.setItemsPerPage(PER_PAGE);
        pagination.addToIterator(contents.newIterator(SlotIterator.Type.HORIZONTAL, 0, 0));

        if (heads.length == 0) {
            contents.set(2, 4, ClickableItem.empty(Items.create(Material.BARRIER, messages.get("gui.targets.empty"), List.of())));
        }

        contents.fillRow(ROWS - 1, kit.filler());
        if (!pagination.isFirst()) {
            int previous = pagination.getPage() - 1;
            contents.set(ROWS - 1, 0, kit.button(kit.item(Material.ARROW, "gui.previous"),
                    () -> contents.inventory().open(viewer, previous)));
        }
        contents.set(ROWS - 1, 4, kit.button(kit.item(Material.NAME_TAG, "gui.targets.enter-name", "gui.targets.enter-name-lore"),
                () -> ui.askForName(viewer)));
        if (!pagination.isLast()) {
            int next = pagination.getPage() + 1;
            contents.set(ROWS - 1, 8, kit.button(kit.item(Material.ARROW, "gui.next"),
                    () -> contents.inventory().open(viewer, next)));
        }
    }

    @Override
    public void update(Player viewer, InventoryContents contents) {
    }
}
