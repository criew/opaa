package io.opaa.indexing;

import java.util.List;

/**
 * One run's header data together with its own protocol (#513) - the pairing {@link
 * DocumentIndexingService#getRecentRuns} hands back to {@code LibraryController}, which maps each
 * one onto the generated {@code IndexingRunResponse} DTO.
 */
public record IndexingRunDetail(IndexingJob job, List<IndexingRunEvent> events) {}
