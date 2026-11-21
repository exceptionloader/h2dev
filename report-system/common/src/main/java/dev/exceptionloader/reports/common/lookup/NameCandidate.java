package dev.exceptionloader.reports.common.lookup;

import dev.exceptionloader.reports.api.model.Edition;

/**
 * One way to interpret a typed player name.
 *
 * @param query   name sent to the lookup API
 * @param edition edition to look up
 */
public record NameCandidate(String query, Edition edition) {
}
