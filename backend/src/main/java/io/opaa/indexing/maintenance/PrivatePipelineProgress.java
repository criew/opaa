package io.opaa.indexing.maintenance;

/**
 * The pipeline-version state of an organization's private libraries as one line, without ids:
 * either the exact number with the summed chunks, or only {@code libraryCountFewerThan}.
 */
public record PrivatePipelineProgress(
    Long libraryCount,
    Integer libraryCountFewerThan,
    Long totalChunks,
    Long currentVersionChunks,
    Long staleChunks) {

  /** The line that tells nothing but "fewer than {@code minimum}". */
  static PrivatePipelineProgress fewerThan(int minimum) {
    return new PrivatePipelineProgress(null, minimum, null, null, null);
  }
}
