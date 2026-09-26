package io.opaa.library;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.DocumentSourceType;
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
 * @param connectorSettings the settings of the library's connector (ADR-0038), never {@code null} -
 *     {@link ConnectorSettingsRequest#NONE} for none
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
    ConnectorSettingsRequest connectorSettings,
    LibraryScheduleUpdate schedule) {}
