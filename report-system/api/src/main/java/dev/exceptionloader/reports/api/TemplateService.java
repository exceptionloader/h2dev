package dev.exceptionloader.reports.api;

import dev.exceptionloader.reports.api.model.ReportTemplate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;

/**
 * In-memory view of the report templates.
 *
 * <p>Reads are served from a snapshot and are safe to call from any thread. The snapshot is
 * replaced as a whole on reload, so readers never see a partially loaded state.
 */
public interface TemplateService {

    /**
     * @return all enabled templates in display order
     */
    @NotNull List<ReportTemplate> templates();

    /**
     * @param id template id, case insensitive
     * @return the template if it exists and is enabled
     */
    @NotNull Optional<ReportTemplate> template(@NotNull String id);

    /**
     * Reloads the templates from the database.
     *
     * @return completes once the new snapshot is active
     */
    @NotNull CompletableFuture<Void> reload();
}
