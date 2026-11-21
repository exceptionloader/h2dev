/**
 * Public API of the report system.
 *
 * <p>Other plugins can obtain the services through the Bukkit {@code ServicesManager}:
 *
 * <pre>{@code
 * ReportService reports = Bukkit.getServicesManager().load(ReportService.class);
 * }</pre>
 *
 * <p>All methods returning a {@link java.util.concurrent.CompletableFuture} complete off the main
 * thread. Callers have to switch back to the main thread themselves before touching the Bukkit API.
 */
package dev.exceptionloader.reports.api;
