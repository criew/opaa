package io.opaa.indexing;

import java.util.Optional;

/**
 * {@link DocumentIndexingService#getStatus}'s result: the library's current or most recently
 * completed run, alongside whether the caller may see a {@code FAILED} run's raw error detail. That
 * detail routinely repeats the library's own {@code sourcePath}/{@code sourceUrl} - an
 * internal-infrastructure leak. {@code canSeeErrorDetail} mirrors {@link
 * DocumentIndexingService#getRecentRuns}'s own {@code MANAGER} bar, so a caller who could not read
 * an {@link IndexingRunEvent#getReference()} there does not get the same information handed back
 * here instead.
 */
public record IndexingStatusView(Optional<IndexingJob> job, boolean canSeeErrorDetail) {}
