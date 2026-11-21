package dev.exceptionloader.reports.api.model;

import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * A selectable report reason. Templates are stored in the {@code templates} collection and can be
 * added or changed while the servers are running.
 *
 * @param id           stable identifier, e.g. {@code cheating}
 * @param name         display name, e.g. {@code CHEATING}
 * @param description  short explanation shown in menus and forms
 * @param icon         material name used by the Java edition menu
 * @param requiresText whether the reporter has to describe the incident in their own words
 * @param sortOrder    position in menus, ascending
 */
public record ReportTemplate(
        @NotNull String id,
        @NotNull String name,
        @NotNull String description,
        @NotNull String icon,
        boolean requiresText,
        int sortOrder) {

    public ReportTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(icon, "icon");
    }
}
