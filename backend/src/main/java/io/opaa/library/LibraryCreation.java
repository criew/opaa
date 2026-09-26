package io.opaa.library;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.knowledge.ConfluenceSpaceSelection;
import io.opaa.knowledge.sourcesettings.S3SourceSettings;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Parameters for {@link KnowledgeLibraryService#createLibrary} - replaces the generated {@code
 * LibraryRequest} at the service boundary (#860): domain services do not know {@code
 * io.opaa.api.dto} types, see AGENTS.md "API &amp; DTO-Konvention". Tests that need a fluent call
 * site use {@code LibraryCreationBuilder} (src/test).
 *
 * @param ownerType {@code null} means {@code USER} (the creator) - the same default the service
 *     applied to a {@code null} {@code LibraryRequest.ownerType}.
 * @param s3Settings the typed configuration of an {@code S3} library (ADR-0027), required for that
 *     type and rejected for every other
 * @param schedule the indexing rhythm to set together with the library (#1942); {@code null} leaves
 *     the library without one, and anything but {@code DISABLED} on an {@code UPLOAD} library is
 *     refused exactly as it is on an update
 */
public record LibraryCreation(
    String name,
    String description,
    AssetOwnerType ownerType,
    UUID ownerId,
    Boolean listed,
    DocumentSourceType sourceType,
    String sourcePath,
    URI sourceUrl,
    String sourceProxy,
    String sourceCredentials,
    Boolean sourceInsecureSsl,
    ConfluenceEdition confluenceEdition,
    List<ConfluenceSpaceSelection> confluenceSpaces,
    Integer confluenceFullSyncIntervalDays,
    S3SourceSettings s3Settings,
    LibraryScheduleUpdate schedule) {}
