package io.opaa.library;

import io.opaa.api.types.AssetRole;
import java.time.Instant;

/**
 * A {@link KnowledgeLibrary} enriched with the caller's effective role, its document count, its
 * owner's resolved display name (#438) and the completion time of its last successful indexing run
 * (#684), as returned by {@link KnowledgeLibraryService#listLibraries} - the domain counterpart of
 * the generated {@code LibraryListResponse}.
 *
 * @param ownerName {@code null} when the owner (user or group) no longer exists or carries no
 *     display name.
 * @param lastIndexedAt {@code null} when the library has never completed an indexing run; failed or
 *     still-running runs never set it.
 */
public record LibrarySummary(
    KnowledgeLibrary library,
    AssetRole myRole,
    long documentCount,
    String ownerName,
    Instant lastIndexedAt) {}
