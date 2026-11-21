package dev.exceptionloader.reports.bukkit.ui.menu;

import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.scheduler.MainThread;
import fr.minuskube.inv.ClickableItem;
import fr.minuskube.inv.InventoryManager;
import fr.minuskube.inv.SmartInventory;
import fr.minuskube.inv.content.InventoryProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/**
 * Shared building blocks of the inventory menus.
 */
@Singleton
public class MenuKit {

    private final InventoryManager inventories;
    private final Messages messages;
    private final MainThread main;

    @Inject
    public MenuKit(InventoryManager inventories, Messages messages, MainThread main) {
        this.inventories = inventories;
        this.messages = messages;
        this.main = main;
    }

    SmartInventory create(String id, int rows, String title, InventoryProvider provider) {
        return SmartInventory.builder()
                .manager(inventories)
                .id(id)
                .provider(provider)
                .size(rows, 9)
                .title(title)
                .build();
    }

    boolean isViewing(Player player, SmartInventory inventory) {
        return inventories.getInventory(player).filter(open -> open == inventory).isPresent();
    }

    ItemStack item(Material material, String key, TagResolver... resolvers) {
        return Items.create(material, messages.get(key, resolvers), List.of());
    }

    ItemStack item(Material material, String key, String loreKey, TagResolver... resolvers) {
        return Items.create(material, messages.get(key, resolvers), messages.lines(loreKey, resolvers));
    }

    ClickableItem filler() {
        return ClickableItem.empty(Items.create(Material.GRAY_STAINED_GLASS_PANE, Component.space(), List.of()));
    }

    /**
     * A button whose action runs on the next tick, outside of the click event. Opening or closing
     * inventories from inside the event is unreliable.
     */
    ClickableItem button(ItemStack item, Runnable action) {
        return ClickableItem.of(item, click -> main.later(action));
    }

    ClickableItem button(ItemStack item, Consumer<Boolean> action) {
        return ClickableItem.of(item, click -> {
            boolean forward = click.getClick() != ClickType.RIGHT && click.getClick() != ClickType.SHIFT_RIGHT;
            main.later(() -> action.accept(forward));
        });
    }

    Messages messages() {
        return messages;
    }

    MainThread main() {
        return main;
    }
}
