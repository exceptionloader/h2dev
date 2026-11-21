package dev.exceptionloader.reports.bukkit.config;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component;
import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import dev.exceptionloader.reports.api.TemplateService;
import dev.exceptionloader.reports.api.model.Report;
import dev.exceptionloader.reports.api.model.ReportStatus;
import dev.exceptionloader.reports.api.model.ReportTemplate;
import dev.exceptionloader.reports.api.query.ReportSort;
import dev.exceptionloader.reports.api.query.TimeRange;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.Nullable;

/**
 * Formatting of report data for chat, menus and forms.
 */
@Singleton
public class Formats {

    private final Messages messages;
    private final TemplateService templates;
    private final DateTimeFormatter dateFormat;

    @Inject
    public Formats(Messages messages, TemplateService templates, PluginSettings settings) {
        this.messages = messages;
        this.templates = templates;
        this.dateFormat = settings.dateFormat();
    }

    /**
     * Placeholders for every field of a report. Player controlled values are inserted unparsed so
     * they cannot inject formatting or click events.
     */
    public TagResolver report(Report report) {
        String none = messages.plain("labels.none");
        return TagResolver.resolver(
                unparsed("id", report.id()),
                unparsed("short_id", shortId(report.id())),
                unparsed("reporter", report.reporterName()),
                unparsed("reported", report.reportedName()),
                unparsed("reason", report.reason()),
                unparsed("template", templateName(report.templateId())),
                unparsed("server", report.server()),
                unparsed("created", date(report.createdAt())),
                component("status", status(report.status())),
                unparsed("handler", Objects.requireNonNullElse(report.handledByName(), none)),
                unparsed("handled", report.handledAt() == null ? none : date(report.handledAt())),
                unparsed("note", Objects.requireNonNullElse(report.modNote(), none)));
    }

    public String date(Instant instant) {
        return dateFormat.format(instant);
    }

    public static String duration(Duration duration) {
        long seconds = Math.max(1, (duration.toMillis() + 999) / 1000);
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        long rest = seconds % 60;
        return rest == 0 ? minutes + "m" : minutes + "m " + rest + "s";
    }

    public Component status(@Nullable ReportStatus status) {
        return messages.get("labels.status." + (status == null ? "ALL" : status.name()));
    }

    public Component range(TimeRange range) {
        return messages.get("labels.range." + range.name());
    }

    public Component sort(ReportSort sort) {
        return messages.get("labels.sort." + sort.name());
    }

    public String templateName(String templateId) {
        return templates.template(templateId)
                .map(ReportTemplate::name)
                .orElse(templateId.toUpperCase(Locale.ROOT));
    }

    /**
     * Last eight characters of the object id. They contain the counter part of the id and are
     * distinct enough to tell reports apart in a list.
     */
    public static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(id.length() - 8);
    }
}
