package dev.exceptionloader.reports.common.storage.entity;

import dev.exceptionloader.reports.api.model.ReportTemplate;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import java.util.Locale;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Document of the {@code templates} collection. Missing optional fields fall back to the defaults
 * below, so a template can be added with nothing but {@code _id}, {@code name} and
 * {@code description}.
 */
@Entity(value = "templates", useDiscriminator = false)
@Getter
@Setter
@NoArgsConstructor
public class TemplateModel {

    @Id
    private String id;
    private String name;
    private String description;
    private String icon = "PAPER";
    private boolean requiresText;
    private int sortOrder = 100;
    private boolean enabled = true;

    public TemplateModel(String id, String name, String description, String icon, boolean requiresText, int sortOrder) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.requiresText = requiresText;
        this.sortOrder = sortOrder;
    }

    public ReportTemplate toTemplate() {
        return new ReportTemplate(
                id.toLowerCase(Locale.ROOT),
                Objects.requireNonNullElse(name, id.toUpperCase(Locale.ROOT)),
                Objects.requireNonNullElse(description, ""),
                Objects.requireNonNullElse(icon, "PAPER"),
                requiresText,
                sortOrder);
    }
}
