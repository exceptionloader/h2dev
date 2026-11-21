package dev.exceptionloader.reports.bukkit.ui;

import dev.exceptionloader.reports.api.model.PlayerProfile;
import dev.exceptionloader.reports.api.model.Report;
import dev.exceptionloader.reports.api.model.ReportTemplate;
import org.bukkit.entity.Player;

/**
 * Screens of the report system. Java players get inventory menus, Bedrock players get forms with
 * the same steps.
 */
public interface ReportUi {

    void showTargets(Player viewer);

    void askForName(Player viewer);

    void showTemplates(Player viewer, PlayerProfile target);

    void askForDetails(Player viewer, PlayerProfile target, ReportTemplate template);

    void showReports(Player viewer, BrowserState state);

    void showReport(Player viewer, Report report, BrowserState returnTo);

    void askForRejectNote(Player viewer, Report report, BrowserState returnTo);
}
