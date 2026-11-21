package dev.exceptionloader.reports.bukkit.ui;

import dev.exceptionloader.reports.bukkit.config.Messages;
import io.papermc.paper.event.player.AsyncChatEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Collects free text from Java players through the chat. The next chat message of a player with
 * an open prompt is swallowed and handed to the prompt instead.
 */
@Singleton
public class ChatPrompts implements Listener {

    private static final Set<String> CANCEL_WORDS = Set.of("abbrechen", "cancel");
    private static final long TIMEOUT_TICKS = 20L * 60;

    private final Plugin plugin;
    private final Messages messages;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    @Inject
    public ChatPrompts(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    /**
     * Asks the player for input. A previous prompt of the same player is replaced.
     */
    public void ask(Player player, Component question, Consumer<String> onAnswer) {
        UUID uuid = player.getUniqueId();
        Prompt prompt = new Prompt(onAnswer);
        Prompt previous = prompts.put(uuid, prompt);
        if (previous != null) {
            previous.cancelTimeout();
        }
        prompt.timeout = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (prompts.remove(uuid, prompt)) {
                messages.send(player, "prompt.expired");
            }
        }, TIMEOUT_TICKS);

        player.closeInventory();
        player.sendMessage(question);
        messages.send(player, "prompt.hint");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Prompt prompt = prompts.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        prompt.cancelTimeout();

        String input = PlainTextComponentSerializer.plainText().serialize(event.originalMessage()).strip();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (CANCEL_WORDS.contains(input.toLowerCase(Locale.ROOT))) {
                messages.send(player, "prompt.cancelled");
                return;
            }
            prompt.onAnswer.accept(input);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Prompt prompt = prompts.remove(event.getPlayer().getUniqueId());
        if (prompt != null) {
            prompt.cancelTimeout();
        }
    }

    private static final class Prompt {

        private final Consumer<String> onAnswer;
        private volatile BukkitTask timeout;

        private Prompt(Consumer<String> onAnswer) {
            this.onAnswer = onAnswer;
        }

        private void cancelTimeout() {
            BukkitTask task = timeout;
            if (task != null) {
                task.cancel();
            }
        }
    }
}
