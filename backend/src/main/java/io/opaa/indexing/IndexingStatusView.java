package io.opaa.indexing;

import java.util.List;
import java.util.Optional;

/**
 * {@link DocumentIndexingService#getStatus}'s result: the library's current or most recently
 * completed run, alongside whether the caller may see a {@code FAILED} run's raw error detail. That
 * detail routinely repeats the library's own {@code sourcePath}/{@code sourceUrl} - an
 * internal-infrastructure leak. {@code canSeeErrorDetail} mirrors {@link
 * DocumentIndexingService#getRecentRuns}'s own {@code MANAGER} bar, so a caller who could not read
 * an {@link IndexingRunEvent#getReference()} there does not get the same information handed back
 * here instead.
 *
 * <p>{@code unreadableSpaceKeys} (#1191) comes from the most recent run that assessed its listing -
 * not necessarily {@code job} - and is empty while that assessment was complete or none exists; see
 * {@link IndexingJobService#getLatestListingAssessment}.
 */
public record IndexingStatusView(
    Optional<IndexingJob> job, boolean canSeeErrorDetail, List<String> unreadableSpaceKeys) {}
