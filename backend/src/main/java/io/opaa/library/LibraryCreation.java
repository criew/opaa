package io.opaa.library;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryOwnerType;
import io.opaa.api.types.LibraryVisibility;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Parameters for {@link KnowledgeLibraryService#createLibrary} - replaces the generated {@code
 * LibraryRequest} at the service boundary (#860): domain services do not know {@code
 * io.opaa.api.dto} types, see AGENTS.md "API & DTO-Konvention". Tests that need a fluent call site
 * use {@code LibraryCreationBuilder} (src/test).
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
    Boolean sourceInsecureSsl,
    ConfluenceEdition confluenceEdition,
    List<ConfluenceSpaceSelection> confluenceSpaces) {}
