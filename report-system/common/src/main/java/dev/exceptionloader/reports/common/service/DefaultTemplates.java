package dev.exceptionloader.reports.common.service;

import dev.exceptionloader.reports.api.model.ReportTemplate;
import dev.exceptionloader.reports.common.storage.entity.TemplateModel;
import java.util.List;

/**
 * Templates written to an empty {@code templates} collection on first start.
 */
final class DefaultTemplates {

    private DefaultTemplates() {
    }

    static List<TemplateModel> models() {
        return List.of(
                new TemplateModel("cheating", "CHEATING", "Verdacht auf Cheats oder Hacks", "DIAMOND_SWORD", false, 10),
                new TemplateModel("insult", "INSULT", "Beleidigung oder toxisches Verhalten", "WRITABLE_BOOK", false, 20),
                new TemplateModel("bugusing", "BUGUSING", "Ausnutzen von Bugs", "SPIDER_EYE", false, 30),
                new TemplateModel("griefing", "GRIEFING", "Zerstörung von Bauwerken", "TNT", false, 40),
                new TemplateModel("spam", "SPAM", "Chat-Spam oder Werbung", "OAK_SIGN", false, 50),
                new TemplateModel("other", "OTHER", "Sonstiges - bitte beschreibe den Vorfall", "NAME_TAG", true, 60));
    }

    static List<ReportTemplate> templates() {
        return models().stream().map(TemplateModel::toTemplate).toList();
    }
}
