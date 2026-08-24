package io.opaa.library;

import io.opaa.indexing.DocumentSourceType;
import java.net.URI;

/**
 * Parameters for {@link KnowledgeLibraryService#updateLibrary} - replaces the generated {@code
 * LibraryUpdateRequest} at the service boundary (#860), see AGENTS.md "API & DTO-Konvention". Tests
 * that need a fluent call site use {@code LibraryUpdateBuilder} (src/test).
 *
 * @param sourceType accepted purely so a caller resending the library's current value is not
 *     rejected for that alone - any value differing from the library's own is rejected. {@code
 *     null} means the caller did not send one.
 * @param schedule {@code null} means the caller does not intend to change the schedule; the stored
 *     one stays untouched. Present (even if {@code DISABLED}) replaces it as a whole.
 */
public record LibraryUpdate(
    String name,
    String description,
    LibraryVisibility visibility,
    Boolean listed,
    DocumentSourceType sourceType,
    String sourcePath,
    URI sourceUrl,
    String sourceProxy,
    String sourceCredentials,
    Boolean sourceInsecureSsl,
    LibraryScheduleUpdate schedule) {}
