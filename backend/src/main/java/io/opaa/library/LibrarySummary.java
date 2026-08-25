package io.opaa.library;

import io.opaa.api.types.AssetRole;

/**
 * A {@link KnowledgeLibrary} enriched with the caller's effective role, its document count and its
 * owner's resolved display name (#438), as returned by {@link
 * KnowledgeLibraryService#listLibraries} - the domain counterpart of the generated {@code
 * LibraryListResponse}.
 *
 * @param ownerName {@code null} when the owner (user or group) no longer exists or carries no
 *     display name.
 */
public record LibrarySummary(
    KnowledgeLibrary library, AssetRole myRole, long documentCount, String ownerName) {}
