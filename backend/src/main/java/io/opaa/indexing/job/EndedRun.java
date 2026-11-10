package io.opaa.indexing.job;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.knowledge.SourceType;
import io.opaa.observability.IndexingMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * An ended run as {@code opaa.indexing.runs} counts it, read together with its library's source
 * type and owner-only mark (both {@code null} once the library is gone). Carries no identifier of
 * run, library or document; a run of an owner-only library counts as {@link
 * IndexingMetrics#PRIVATE}, with its person-bound failure categories folded into that one too.
 */
public record EndedRun(
    SourceType sourceType,
    Boolean ownerOnly,
    IndexingRunMode runMode,
    JobStatus status,
    String failureCategory,
    boolean incomplete,
    Instant startedAt,
    Instant completedAt,
    Long bytesDownloaded) {

  /** The {@code failure_category} of a run the recovery failed for its lost owner or heartbeat. */
  static final String ABANDONED = "abandoned";

  /**
   * Stored failure categories that speak of the one person behind an owner-only library, named as
   * {@code RunFailureCategory} stores them; the job layer sits below the connector SPI.
   */
  private static final Set<String> PERSON_BOUND = Set.of("QUOTA_EXHAUSTED", "OWNER_DEACTIVATED");

  /** Records this run in {@code metrics}; {@code category} overrides the stored one if not null. */
  void recordIn(IndexingMetrics metrics, String category) {
    boolean ownersOwn = Boolean.TRUE.equals(ownerOnly);
    metrics.recordRunEnded(
        ownersOwn
            ? IndexingMetrics.PRIVATE
            : IndexingMetrics.connector(sourceType == null ? null : sourceType.key()),
        mode(),
        result(),
        category != null ? category : failureCategoryTag(ownersOwn),
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

  private String failureCategoryTag(boolean ownersOwn) {
    if (failureCategory == null) {
      return IndexingMetrics.NO_CATEGORY;
    }
    if (ownersOwn && PERSON_BOUND.contains(failureCategory)) {
      return IndexingMetrics.PRIVATE;
    }
    return failureCategory.toLowerCase(Locale.ROOT);
  }
}
