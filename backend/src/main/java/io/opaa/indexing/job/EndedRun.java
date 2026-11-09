package io.opaa.indexing.job;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.knowledge.SourceType;
import io.opaa.observability.IndexingMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * An ended run as {@code opaa.indexing.runs} counts it, read together with its library's source
 * type ({@code null} once the library is gone). Carries no identifier of run, library or document.
 */
public record EndedRun(
    SourceType sourceType,
    IndexingRunMode runMode,
    JobStatus status,
    String failureCategory,
    boolean incomplete,
    Instant startedAt,
    Instant completedAt,
    Long bytesDownloaded) {

  /** The {@code failure_category} of a run the recovery failed for its lost owner or heartbeat. */
  static final String ABANDONED = "abandoned";

  /** Records this run in {@code metrics}; {@code category} overrides the stored one if not null. */
  void recordIn(IndexingMetrics metrics, String category) {
    metrics.recordRunEnded(
        sourceType == null ? null : sourceType.key(),
        mode(),
        result(),
        category != null ? category : failureCategoryTag(),
        startedAt == null || completedAt == null ? null : Duration.between(startedAt, completedAt),
        bytesDownloaded == null ? 0L : bytesDownloaded);
  }

  private String mode() {
    if (runMode == null) {
      return "full";
    }
    return switch (runMode) {
      case FULL -> "full";
      case INCREMENTAL -> "delta";
      case EVENT -> "event";
    };
  }

  private String result() {
    if (status == JobStatus.FAILED) {
      return "failed";
    }
    return incomplete ? "incomplete" : "completed";
  }

  private String failureCategoryTag() {
    return failureCategory == null
        ? IndexingMetrics.NO_CATEGORY
        : failureCategory.toLowerCase(Locale.ROOT);
  }
}
