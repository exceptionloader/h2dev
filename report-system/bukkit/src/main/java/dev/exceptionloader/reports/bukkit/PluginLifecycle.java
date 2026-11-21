package dev.exceptionloader.reports.bukkit;

import dev.exceptionloader.reports.api.ProfileService;
import dev.exceptionloader.reports.api.ReportService;
import dev.exceptionloader.reports.api.TemplateService;
import dev.exceptionloader.reports.api.message.ReportChannels;
import dev.exceptionloader.reports.api.message.ReportCreatedMessage;
import dev.exceptionloader.reports.api.message.ReportStatusMessage;
import dev.exceptionloader.reports.api.message.TemplateReloadMessage;
import dev.exceptionloader.reports.bukkit.command.ReportCommand;
import dev.exceptionloader.reports.bukkit.command.ReportsCommand;
import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.config.PluginSettings;
import dev.exceptionloader.reports.bukkit.listener.ConnectionListener;
import dev.exceptionloader.reports.bukkit.notify.ReporterNotifier;
import dev.exceptionloader.reports.bukkit.notify.StaffAlerts;
import dev.exceptionloader.reports.bukkit.ui.ChatPrompts;
import dev.exceptionloader.reports.common.inject.PluginLogger;
import dev.exceptionloader.reports.common.lookup.MinecraftApiClient;
import dev.exceptionloader.reports.common.messaging.MessageBus;
import dev.exceptionloader.reports.common.messaging.RedisConnector;
import dev.exceptionloader.reports.common.scheduler.PlatformScheduler;
import dev.exceptionloader.reports.common.storage.MongoConnector;
import fr.minuskube.inv.InventoryManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

/**
 * Wires the components together on enable and tears them down in reverse order on disable.
 */
@Singleton
public class PluginLifecycle {

    private static final Duration DATABASE_RETRY = Duration.ofSeconds(30);

    private final ReportPlugin plugin;
    private final PluginSettings settings;
    private final PlatformScheduler scheduler;
    private final Logger logger;
    private final MongoConnector mongo;
    private final RedisConnector redis;
    private final MessageBus bus;
    private final MinecraftApiClient api;
    private final Messages messages;
    private final ReportService reports;
    private final TemplateService templates;
    private final ProfileService profiles;
    private final StaffAlerts staffAlerts;
    private final ReporterNotifier reporterNotifier;
    private final ConnectionListener connectionListener;
    private final ChatPrompts chatPrompts;
    private final ReportCommand reportCommand;
    private final ReportsCommand reportsCommand;
    private final List<PlatformScheduler.Task> tasks = new ArrayList<>();

    @Inject
    public PluginLifecycle(
            ReportPlugin plugin,
            PluginSettings settings,
            PlatformScheduler scheduler,
            @PluginLogger Logger logger,
            MongoConnector mongo,
            RedisConnector redis,
            MessageBus bus,
            MinecraftApiClient api,
            Messages messages,
            ReportService reports,
            TemplateService templates,
            ProfileService profiles,
            StaffAlerts staffAlerts,
            ReporterNotifier reporterNotifier,
            ConnectionListener connectionListener,
            ChatPrompts chatPrompts,
            ReportCommand reportCommand,
            ReportsCommand reportsCommand,
            // requested here so SmartInvs registers its listeners while the plugin enables
            InventoryManager inventories) {
        this.plugin = plugin;
        this.settings = settings;
        this.scheduler = scheduler;
        this.logger = logger;
        this.mongo = mongo;
        this.redis = redis;
        this.bus = bus;
        this.api = api;
        this.messages = messages;
        this.reports = reports;
        this.templates = templates;
        this.profiles = profiles;
        this.staffAlerts = staffAlerts;
        this.reporterNotifier = reporterNotifier;
        this.connectionListener = connectionListener;
        this.chatPrompts = chatPrompts;
        this.reportCommand = reportCommand;
        this.reportsCommand = reportsCommand;
    }

    public void start() {
        messages.load();
        scheduler.runAsync(this::prepareDatabase);

        bus.subscribe(ReportChannels.NEW_REPORT, ReportCreatedMessage.class, staffAlerts::onReportCreated);
        bus.subscribe(ReportChannels.STATUS_UPDATE, ReportStatusMessage.class, reporterNotifier::onStatusUpdate);
        bus.subscribe(ReportChannels.TEMPLATE_RELOAD, TemplateReloadMessage.class, this::onTemplateReload);
        bus.start();
        scheduler.runAsync(this::checkRedis);

        PluginManager plugins = plugin.getServer().getPluginManager();
        plugins.registerEvents(connectionListener, plugin);
        plugins.registerEvents(chatPrompts, plugin);
        registerCommand("report", reportCommand);
        registerCommand("reports", reportsCommand);

        Duration refresh = settings.templateRefresh();
        tasks.add(scheduler.runAsyncTimer(templates::reload, refresh, refresh));

        ServicesManager services = plugin.getServer().getServicesManager();
        services.register(ReportService.class, reports, plugin, ServicePriority.Normal);
        services.register(TemplateService.class, templates, plugin, ServicePriority.Normal);
        services.register(ProfileService.class, profiles, plugin, ServicePriority.Normal);

        logger.info("Report system running as '" + settings.reports().serverName() + "'");
    }

    public void stop() {
        tasks.forEach(PlatformScheduler.Task::cancel);
        tasks.clear();
        plugin.getServer().getServicesManager().unregisterAll(plugin);
        closeQuietly("message bus", bus);
        closeQuietly("lookup client", api);
        closeQuietly("Redis", redis);
        closeQuietly("MongoDB", mongo);
    }

    private void prepareDatabase() {
        try {
            mongo.ping();
            mongo.datastore();
            templates.reload().join();
            logger.info("Connected to MongoDB");
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "MongoDB is not reachable, retrying in " + DATABASE_RETRY.toSeconds() + "s: " + exception.getMessage());
            if (plugin.isEnabled()) {
                scheduler.runAsyncLater(this::prepareDatabase, DATABASE_RETRY);
            }
        }
    }

    private void checkRedis() {
        try {
            redis.execute(jedis -> jedis.ping());
            logger.info("Connected to Redis");
        } catch (RuntimeException exception) {
            logger.warning("Redis is not reachable yet, live notifications start once it is: " + exception.getMessage());
        }
    }

    private void onTemplateReload(TemplateReloadMessage message) {
        if (!settings.reports().serverName().equals(message.origin())) {
            templates.reload();
        }
    }

    private void registerCommand(String name, TabExecutor executor) {
        PluginCommand command = Objects.requireNonNull(plugin.getCommand(name), () -> "command " + name + " missing in plugin.yml");
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void closeQuietly(String name, AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Could not close " + name, exception);
        }
    }
}
