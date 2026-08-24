package io.opaa.library;

import io.opaa.indexing.DocumentSourceType;
import java.net.URI;
import java.util.UUID;

/**
 * Parameters for {@link KnowledgeLibraryService#createLibrary} - replaces the generated {@code
 * LibraryRequest} at the service boundary (#860): domain services do not know {@code
 * io.opaa.api.dto} types, see AGENTS.md "API & DTO-Konvention". Immutable, fluent {@code
 * withX}-style setters mirror {@code LibraryRequest}'s generated builder for a low-friction test
 * call site.
 *
 * @param ownerType {@code null} means {@code USER} (the creator) - the same default the service
 *     applied to a {@code null} {@code LibraryRequest.ownerType}.
 */
public record LibraryCreation(
    String name,
    String description,
    LibraryOwnerType ownerType,
    UUID ownerId,
    LibraryVisibility visibility,
    Boolean listed,
    DocumentSourceType sourceType,
    String sourcePath,
    URI sourceUrl,
    String sourceProxy,
    String sourceCredentials,
    Boolean sourceInsecureSsl) {

  public LibraryCreation(String name, DocumentSourceType sourceType) {
    this(name, null, null, null, null, null, sourceType, null, null, null, null, null);
  }

  public LibraryCreation description(String description) {
    return new LibraryCreation(
        name,
        description,
        ownerType,
        ownerId,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl);
  }

  public LibraryCreation ownerType(LibraryOwnerType ownerType) {
    return new LibraryCreation(
        name,
        description,
        ownerType,
        ownerId,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl);
  }

  public LibraryCreation ownerId(UUID ownerId) {
    return new LibraryCreation(
        name,
        description,
        ownerType,
        ownerId,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl);
  }

  public LibraryCreation visibility(LibraryVisibility visibility) {
    return new LibraryCreation(
        name,
        description,
        ownerType,
        ownerId,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl);
  }

  public LibraryCreation listed(Boolean listed) {
    return new LibraryCreation(
        name,
        description,
        ownerType,
        ownerId,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl);
  }

  public LibraryCreation sourcePath(String sourcePath) {
    return new LibraryCreation(
        name,
        description,
        ownerType,
        ownerId,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl);
  }

  public LibraryCreation sourceUrl(URI sourceUrl) {
    return new LibraryCreation(
        name,
        description,
        ownerType,
        ownerId,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl);
  }

  public LibraryCreation sourceProxy(String sourceProxy) {
    return new LibraryCreation(
        name,
        description,
        ownerType,
        ownerId,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl);
  }

  public LibraryCreation sourceCredentials(String sourceCredentials) {
    return new LibraryCreation(
        name,
        description,
        ownerType,
        ownerId,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl);
  }

  public LibraryCreation sourceInsecureSsl(Boolean sourceInsecureSsl) {
    return new LibraryCreation(
        name,
        description,
        ownerType,
        ownerId,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl);
  }
}
