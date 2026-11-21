package dev.exceptionloader.reports.bukkit.listener;

import dev.exceptionloader.reports.api.model.PlayerProfile;
import dev.exceptionloader.reports.bukkit.flow.TargetResolver;
import dev.exceptionloader.reports.bukkit.notify.ReporterNotifier;
import dev.exceptionloader.reports.bukkit.scheduler.MainThread;
import dev.exceptionloader.reports.common.lookup.DefaultProfileService;
import dev.exceptionloader.reports.common.service.PlayerDirectory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

@Singleton
public class ConnectionListener implements Listener {

    // let join messages of other plugins pass first, otherwise the result scrolls away immediately
    private static final long PENDING_DELAY_TICKS = 40;

    private final TargetResolver targets;
    private final PlayerDirectory directory;
    private final DefaultProfileService profiles;
    private final ReporterNotifier notifier;
    private final MainThread main;

    @Inject
    public ConnectionListener(
            TargetResolver targets,
            PlayerDirectory directory,
            DefaultProfileService profiles,
            ReporterNotifier notifier,
            MainThread main) {
        this.targets = targets;
        this.directory = directory;
        this.profiles = profiles;
        this.notifier = notifier;
        this.main = main;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerProfile profile = targets.profileOf(player);
        profiles.prime(profile);
        directory.record(profile);
        main.later(() -> {
            if (player.isOnline()) {
                notifier.deliverPending(player);
            }
        }, PENDING_DELAY_TICKS);
    }
}
