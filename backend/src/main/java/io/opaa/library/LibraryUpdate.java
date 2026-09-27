package io.opaa.library;

import io.opaa.indexing.source.ConnectorData;
import io.opaa.knowledge.SourceType;
import java.net.URI;

/**
 * Parameters for {@link KnowledgeLibraryService#updateLibrary} - replaces the generated {@code
 * LibraryUpdateRequest} at the service boundary (#860), see AGENTS.md "API &amp; DTO-Konvention".
 * Tests that need a fluent call site use {@code LibraryUpdateBuilder} (src/test).
 *
 * @param sourceType accepted purely so a caller resending the library's current value is not
 *     rejected for that alone - any value differing from the library's own is rejected. {@code
 *     null} means the caller did not send one.
 * @param schedule {@code null} means the caller does not intend to change the schedule; the stored
 *     one stays untouched. Present (even if {@code DISABLED}) replaces it as a whole.
 * @param sourceSettings the change of the connector's settings (ADR-0038); {@code null} leaves them
 *     untouched, and what a present one replaces the connector decides
 */
public record LibraryUpdate(
    String name,
    String description,
    Boolean listed,
    SourceType sourceType,
    String sourcePath,
    URI sourceUrl,
    String sourceProxy,
    String sourceCredentials,
    Boolean sourceInsecureSsl,
    LibraryScheduleUpdate schedule,
    ConnectorData sourceSettings) {}
