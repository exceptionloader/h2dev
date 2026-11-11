package de.minesmp.core.display;

import de.minesmp.core.MineSMP;
import de.minesmp.core.util.Msg;
import de.minesmp.core.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Chat-Format (Rang-Praefix aus LuckPerms ueber Vault, Clan-Tag), Anti-Spam (Tempo, Wiederholung,
 * Grossbuchstaben, Werbung, Wortfilter), Mute-Pruefung, Clanchat-Modus und Erwaehnungen.
 */
public final class ChatService implements Listener {
    private static final Pattern LINK = Pattern.compile("(?i)\\b((https?://)?[a-z0-9-]+(\\.|\\s?\\(?dot\\)?\\s?)(de|com|net|org|eu|gg|io|me|tk|xyz|ru|at|ch|co|cc|tv)\\b|\\d{1,3}(\\.\\d{1,3}){3})");

    private final MineSMP plugin;
    private Chat vaultChat;
    private final Map<UUID, String> prefixCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastChat = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastMessage = new ConcurrentHashMap<>();
    private final List<String> blocked;
    private final List<String> allowedDomains;

    public ChatService(MineSMP plugin) {
        this.plugin = plugin;
        blocked = plugin.getConfig().getStringList("chat.blocked-words");
        allowedDomains = plugin.getConfig().getStringList("chat.allowed-domains");
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Chat> rsp = Bukkit.getServicesManager().getRegistration(Chat.class);
            if (rsp != null) vaultChat = rsp.getProvider();
        }
        if (vaultChat == null) {
            // LuckPerms registriert den Vault-Chat-Dienst evtl. erst nach uns
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                RegisteredServiceProvider<Chat> rsp = Bukkit.getServicesManager().getRegistration(Chat.class);
                if (rsp != null) vaultChat = rsp.getProvider();
                refreshPrefixes();
            }, 40);
        }
        plugin.listen(this);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshPrefixes, 40, 100);
    }

    private void refreshPrefixes() {
        for (Player p : Bukkit.getOnlinePlayers()) prefixCache.put(p.getUniqueId(), loadPrefix(p));
    }

    private String loadPrefix(Player p) {
        String pre = null;
        if (vaultChat != null) {
            try {
                pre = vaultChat.getPlayerPrefix(p);
            } catch (Exception ignored) {
            }
        }
        if (pre == null || pre.isEmpty()) pre = plugin.getConfig().getString("chat.default-prefix", "&7");
        return pre;
    }

    /** Rang-Praefix (z.B. "&c&lADMIN &8| &c"). */
    public String prefix(Player p) {
        return prefixCache.computeIfAbsent(p.getUniqueId(), k -> loadPrefix(p));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        prefixCache.put(e.getPlayer().getUniqueId(), loadPrefix(e.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        prefixCache.remove(id);
        lastChat.remove(id);
        lastMessage.remove(id);
    }

    /** Anti-Spam-Pruefung (auch fuer /msg). false = Nachricht blockieren. */
    public boolean allowMessage(Player p, String msg) {
        if (p.hasPermission("minesmp.chat.bypass")) return true;
        long now = System.currentTimeMillis();
        long delay = plugin.getConfig().getLong("chat.delay-ms", 1500);
        Long last = lastChat.get(p.getUniqueId());
        if (last != null && now - last < delay) {
            Msg.send(p, "chat.slow", "{prefix}&cBitte schreibe nicht so schnell.");
            return false;
        }
        String norm = msg.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (norm.length() > 3 && norm.equals(lastMessage.get(p.getUniqueId()))) {
            Msg.send(p, "chat.repeat", "{prefix}&cBitte wiederhole dich nicht.");
            return false;
        }
        if (!p.hasPermission("minesmp.chat.links")) {
            var m = LINK.matcher(msg);
            while (m.find()) {
                String found = m.group().toLowerCase(Locale.ROOT);
                if (allowedDomains.stream().noneMatch(found::contains)) {
                    Msg.send(p, "chat.link", "{prefix}&cWerbung und Links sind im Chat nicht erlaubt.");
                    plugin.audit().log(p.getName(), "CHAT_LINK_BLOCKED", msg);
                    return false;
                }
            }
        }
        lastChat.put(p.getUniqueId(), now);
        lastMessage.put(p.getUniqueId(), norm);
        return true;
    }

    private String filter(Player p, String msg) {
        if (!p.hasPermission("minesmp.chat.bypass")) {
            long upper = msg.chars().filter(Character::isUpperCase).count();
            if (msg.length() > 6 && upper > msg.length() * plugin.getConfig().getDouble("chat.max-caps-ratio", 0.6)) msg = msg.toLowerCase(Locale.ROOT);
            for (String w : blocked) {
                if (w.isBlank()) continue;
                msg = msg.replaceAll("(?i)" + Pattern.quote(w), "*".repeat(w.length()));
            }
        }
        if (!p.hasPermission("minesmp.chat.color")) msg = msg.replace("&", "");
        return msg;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        String raw = Text.plain(e.message());
        // Beschreibung fuer einen Report (/report): erscheint nicht im Chat
        if (plugin.reports() != null && plugin.reports().consumeChat(p, raw)) {
            e.setCancelled(true);
            return;
        }
        var mute = plugin.punish().mute(p);
        if (mute != null) {
            e.setCancelled(true);
            Msg.send(p, "mute.active-reason", "{prefix}&cDu bist stummgeschaltet. &7Grund: &f{reason}", "reason", mute.reason());
            return;
        }
        if (plugin.clans().isClanChat(p) && !raw.startsWith("!")) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> plugin.clans().chat(p, raw));
            return;
        }
        String msgText = raw.startsWith("!") && plugin.clans().isClanChat(p) ? raw.substring(1) : raw;
        if (!allowMessage(p, msgText)) {
            e.setCancelled(true);
            return;
        }
        String clean = filter(p, msgText);
        // Erwaehnungen hervorheben
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p) || !clean.toLowerCase(Locale.ROOT).contains(o.getName().toLowerCase(Locale.ROOT))) continue;
            clean = clean.replaceAll("(?i)\\b" + Pattern.quote(o.getName()) + "\\b", "&b@" + o.getName() + "&f");
            if (plugin.data().getIfLoaded(o.getUniqueId()) != null && plugin.data().getIfLoaded(o.getUniqueId()).setting("mentions", true)) {
                Bukkit.getScheduler().runTask(plugin, () -> o.playSound(o.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.6f));
            }
        }
        // Ignorierte Spieler herausfiltern
        String id = p.getUniqueId().toString();
        e.viewers().removeIf(a -> a instanceof Player v && plugin.data().getIfLoaded(v.getUniqueId()) != null
                && plugin.data().getIfLoaded(v.getUniqueId()).ignored.contains(id));
        String fmt = plugin.getConfig().getString("chat.format", "{prefix}{clan}&f{name} &8» &f{message}");
        String head = Text.replace(fmt.substring(0, Math.max(0, fmt.indexOf("{message}"))), "prefix", prefix(p), "clan", plugin.clans().tag(p), "name", p.getName());
        Component name = Text.c(head)
                .hoverEvent(HoverEvent.showText(Text.c(Msg.get("chat.hover", "&b{name}\n&7Level: &e{level}\n&7Clan: &f{clan}\n&7Kills: &c{kills}\n\n&7Klicke fuer Profil",
                        "name", p.getName(), "level", String.valueOf(plugin.data().get(p).level),
                        "clan", plugin.clans().of(p) == null ? "-" : plugin.clans().of(p).name, "kills", String.valueOf(plugin.data().get(p).kills)))))
                .clickEvent(ClickEvent.runCommand("/profile " + p.getName()));
        Component body = Text.c(clean);
        Component packName = name;
        if (plugin.ranks() != null && plugin.pack() != null) {
            String pf = plugin.getConfig().getString("chat.format-pack", "{badge} {clan}{name} &8» &f{message}");
            String ph = Text.replace(pf.substring(0, Math.max(0, pf.indexOf("{message}"))), "badge", plugin.ranks().badge(p),
                    "clan", plugin.clans().tag(p), "name", plugin.ranks().name(p), "prefix", prefix(p));
            packName = Text.c(ph).hoverEvent(name.hoverEvent()).clickEvent(name.clickEvent());
        }
        Component packHead = packName;
        e.renderer((source, displayName, message, viewer) ->
                (viewer instanceof Player vp && plugin.pack() != null && plugin.pack().has(vp) ? packHead : name).append(body));
    }
}
