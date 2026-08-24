package io.opaa.library;

import io.opaa.indexing.DocumentSourceType;
import java.net.URI;

/**
 * Parameters for {@link KnowledgeLibraryService#updateLibrary} - replaces the generated {@code
 * LibraryUpdateRequest} at the service boundary (#860), see AGENTS.md "API & DTO-Konvention".
 * Immutable, fluent {@code withX}-style setters mirror {@code LibraryUpdateRequest}'s generated
 * builder for a low-friction test call site.
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
    LibraryScheduleUpdate schedule) {

  public LibraryUpdate(String name) {
    this(name, null, null, null, null, null, null, null, null, null, null);
  }

  public LibraryUpdate description(String description) {
    return new LibraryUpdate(
        name,
        description,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        schedule);
  }

  public LibraryUpdate visibility(LibraryVisibility visibility) {
    return new LibraryUpdate(
        name,
        description,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        schedule);
  }

  public LibraryUpdate listed(Boolean listed) {
    return new LibraryUpdate(
        name,
        description,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        schedule);
  }

  public LibraryUpdate sourceType(DocumentSourceType sourceType) {
    return new LibraryUpdate(
        name,
        description,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        schedule);
  }

  public LibraryUpdate sourcePath(String sourcePath) {
    return new LibraryUpdate(
        name,
        description,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        schedule);
  }

  public LibraryUpdate sourceUrl(URI sourceUrl) {
    return new LibraryUpdate(
        name,
        description,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        schedule);
  }

  public LibraryUpdate sourceProxy(String sourceProxy) {
    return new LibraryUpdate(
        name,
        description,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        schedule);
  }

  public LibraryUpdate sourceCredentials(String sourceCredentials) {
    return new LibraryUpdate(
        name,
        description,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        schedule);
  }

  public LibraryUpdate sourceInsecureSsl(Boolean sourceInsecureSsl) {
    return new LibraryUpdate(
        name,
        description,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        schedule);
  }

  public LibraryUpdate schedule(LibraryScheduleUpdate schedule) {
    return new LibraryUpdate(
        name,
        description,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        schedule);
  }
}
