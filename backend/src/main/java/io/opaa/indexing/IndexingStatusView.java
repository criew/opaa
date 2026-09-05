package io.opaa.indexing;

import java.util.List;
import java.util.Optional;

/**
 * {@link DocumentIndexingService#getStatus}'s result: the library's current or most recently
 * completed run, alongside whether the caller may see a {@code FAILED} run's raw error detail. That
 * detail routinely repeats the library's own {@code sourcePath}/{@code sourceUrl}, so {@code
 * canSeeErrorDetail} mirrors {@link DocumentIndexingService#getRecentRuns}'s {@code MANAGER} bar.
 * {@code unreadableSpaceKeys} comes from the most recent run that assessed its listing - not
 * necessarily {@code job} - and is empty while that assessment was complete or none exists.
 */
public record IndexingStatusView(
    Optional<IndexingJob> job, boolean canSeeErrorDetail, List<String> unreadableSpaceKeys) {}
