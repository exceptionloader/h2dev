package dev.exceptionloader.reports.bukkit.notify;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import dev.exceptionloader.reports.api.message.ReportStatusMessage;
import dev.exceptionloader.reports.api.model.Report;
import dev.exceptionloader.reports.api.model.ReportStatus;
import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.scheduler.MainThread;
import dev.exceptionloader.reports.common.inject.PluginLogger;
import dev.exceptionloader.reports.common.service.ReporterInbox;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/**
 * Tells reporters how their report was decided, no matter which instance or proxy they are on.
 *
 * <ol>
 *     <li>The status update is broadcast to every instance.</li>
 *     <li>Only the instance the reporter is connected to tries to claim the notification.</li>
 *     <li>The claim is atomic in MongoDB, so even a player caught between two servers is told once.</li>
 *     <li>Results nobody could deliver stay unclaimed and are delivered on the next join.</li>
 * </ol>
 */
@Singleton
public class ReporterNotifier {

    private final Server server;
    private final ReporterInbox inbox;
    private final Messages messages;
    private final MainThread main;
    private final Logger logger;

    @Inject
    public ReporterNotifier(Server server, ReporterInbox inbox, Messages messages, MainThread main, @PluginLogger Logger logger) {
        this.server = server;
        this.inbox = inbox;
        this.messages = messages;
        this.main = main;
        this.logger = logger;
    }

    public void onStatusUpdate(ReportStatusMessage message) {
        if (message.reportId() == null || message.reporterUuid() == null) {
            return;
        }
        main.run(() -> {
            if (server.getPlayer(message.reporterUuid()) == null) {
                return;
            }
            inbox.claim(message.reportId()).whenCompleteAsync((claimed, error) -> {
                if (error != null) {
                    logger.warning("Could not claim notification of report " + message.reportId() + ": " + error.getMessage());
                    return;
                }
                claimed.ifPresent(this::deliver);
            }, main.executor());
        });
    }

    public void deliverPending(Player player) {
        inbox.claimPending(player.getUniqueId()).whenCompleteAsync((claimed, error) -> {
            if (error != null) {
                logger.warning("Could not load pending report results of " + player.getName() + ": " + error.getMessage());
                return;
            }
            claimed.forEach(this::deliver);
        }, main.executor());
    }

    private void deliver(Report report) {
        Player reporter = server.getPlayer(report.reporter());
        if (reporter == null) {
            // left between claim and delivery, try again on the next join
            inbox.release(report.id());
            return;
        }
        String key = report.status() == ReportStatus.REJECTED ? "notify.rejected" : "notify.resolved";
        messages.send(reporter, key,
                unparsed("reported", report.reportedName()),
                unparsed("note", Objects.requireNonNullElse(report.modNote(), messages.plain("labels.none"))));
    }
}
