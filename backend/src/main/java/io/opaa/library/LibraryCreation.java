package io.opaa.library;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.knowledge.SourceType;
import java.net.URI;
import java.util.UUID;

/**
 * Parameters for {@link KnowledgeLibraryService#createLibrary} - replaces the generated {@code
 * LibraryRequest} at the service boundary (#860): domain services do not know {@code
 * io.opaa.api.dto} types, see AGENTS.md "API &amp; DTO-Konvention". Tests that need a fluent call
 * site use {@code LibraryCreationBuilder} (src/test).
 *
 * @param ownerType {@code null} means {@code USER} (the creator) - the same default the service
 *     applied to a {@code null} {@code LibraryRequest.ownerType}.
 * @param sourceSettings the settings of the library's connector (ADR-0038), {@code null} for none
 * @param schedule the indexing rhythm to set together with the library (#1942); {@code null} leaves
 *     the library without one, and anything but {@code DISABLED} on an {@code UPLOAD} library is
 *     refused exactly as it is on an update
 * @param connectionProfileId the connection profile the library is created on (#2160), {@code null}
 *     for a library with its own address
 */
public record LibraryCreation(
    String name,
    String description,
    AssetOwnerType ownerType,
    UUID ownerId,
    SourceType sourceType,
    String sourcePath,
    URI sourceUrl,
    String sourceProxy,
    String sourceCredentials,
    Boolean sourceInsecureSsl,
    ConnectorData sourceSettings,
    LibraryScheduleUpdate schedule,
    UUID connectionProfileId) {

  /** A library with its own address. */
  public LibraryCreation(
      String name,
      String description,
      AssetOwnerType ownerType,
      UUID ownerId,
      SourceType sourceType,
      String sourcePath,
      URI sourceUrl,
      String sourceProxy,
      String sourceCredentials,
      Boolean sourceInsecureSsl,
      ConnectorData sourceSettings,
      LibraryScheduleUpdate schedule) {
    this(
        name,
        description,
        ownerType,
        ownerId,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        sourceSettings,
        schedule,
        null);
  }

  /** This request with {@code url} as its address. */
  LibraryCreation withSourceUrl(URI url) {
    return new LibraryCreation(
        name,
        description,
        ownerType,
        ownerId,
        sourceType,
        sourcePath,
        url,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        sourceSettings,
        schedule,
        connectionProfileId);
  }
}
