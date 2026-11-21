package dev.exceptionloader.reports.bukkit.command;

import dev.exceptionloader.reports.api.TemplateService;
import dev.exceptionloader.reports.api.message.ReportChannels;
import dev.exceptionloader.reports.api.message.TemplateReloadMessage;
import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.bukkit.scheduler.MainThread;
import dev.exceptionloader.reports.common.config.ReportSettings;
import dev.exceptionloader.reports.common.messaging.MessageBus;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import org.bukkit.command.CommandSender;

/**
 * Reloads messages and templates here and asks all other instances to reload their templates.
 * Connection settings in {@code config.yml} require a restart.
 */
@Singleton
class ReloadSubcommand implements Subcommand {

    private final Messages messages;
    private final TemplateService templates;
    private final MessageBus bus;
    private final MainThread main;
    private final ReportSettings settings;

    @Inject
    ReloadSubcommand(Messages messages, TemplateService templates, MessageBus bus, MainThread main, ReportSettings settings) {
        this.messages = messages;
        this.templates = templates;
        this.bus = bus;
        this.main = main;
        this.settings = settings;
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        messages.load();
        main.handle(templates.reload(), sender, ignored -> {
            bus.publish(ReportChannels.TEMPLATE_RELOAD, new TemplateReloadMessage(settings.serverName(), Instant.now()));
            messages.send(sender, "moderation.reloaded");
        });
    }
}
