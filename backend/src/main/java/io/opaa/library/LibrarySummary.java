package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.indexing.job.JobStatus;
import io.opaa.permission.AssetReach;
import io.opaa.permission.SuccessionFinding;
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
 * @param lastRunStatus the outcome of the newest run whatever it was (#1940), {@code null} when the
 *     library has never been indexed - the one thing {@code lastIndexedAt} cannot say, since a
 *     failed last run leaves it standing at the previous success.
 * @param succession the derived state "Nachfolge offen" (ADR-0036, Entscheidung 6), {@code null}
 *     while the library has a capable owner - the overview carries the marking as the detail view
 *     does, reduced to state and addressee by the mapper.
 * @param reach how far the library reaches right now, derived from its grants (#1931) - the
 *     overview shows it as a badge instead of the former release level.
 */
public record LibrarySummary(
    KnowledgeLibrary library,
    AssetRole myRole,
    long documentCount,
    String ownerName,
    Instant lastIndexedAt,
    JobStatus lastRunStatus,
    SuccessionFinding succession,
    AssetReach reach) {}
