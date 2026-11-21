package dev.exceptionloader.reports.bukkit.notify;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import dev.exceptionloader.reports.api.message.ReportCreatedMessage;
import dev.exceptionloader.reports.bukkit.Permissions;
import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.scheduler.MainThread;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Server;

/**
 * Announces new reports to the staff on this instance. Every instance receives the broadcast and
 * only informs its own players, so each moderator gets the alert exactly once.
 */
@Singleton
public class StaffAlerts {

    private final Server server;
    private final Messages messages;
    private final MainThread main;

    @Inject
    public StaffAlerts(Server server, Messages messages, MainThread main) {
        this.server = server;
        this.messages = messages;
        this.main = main;
    }

    public void onReportCreated(ReportCreatedMessage message) {
        if (message.reportId() == null) {
            return;
        }
        main.run(() -> {
            String reportServer = Objects.requireNonNullElse(message.server(), "?");
            TagResolver placeholders = TagResolver.resolver(
                    unparsed("id", message.reportId()),
                    unparsed("reporter", Objects.requireNonNullElse(message.reporter(), "?")),
                    unparsed("reported", Objects.requireNonNullElse(message.reported(), "?")),
                    unparsed("reason", Objects.requireNonNullElse(message.reason(), "?")),
                    unparsed("server", reportServer),
                    Placeholder.styling("open_report",
                            ClickEvent.runCommand("/report details " + message.reportId()),
                            HoverEvent.showText(messages.get("alert.report-hover", unparsed("server", reportServer)))),
                    Placeholder.styling("open_reports",
                            ClickEvent.runCommand("/reports"),
                            HoverEvent.showText(messages.get("alert.reports-hover"))));
            for (Component line : messages.lines("alert.new-report", placeholders)) {
                server.broadcast(line, Permissions.ADMIN);
            }
        });
    }
}
