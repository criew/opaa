package io.opaa.indexing.chunk;

import java.time.Instant;
import java.util.UUID;

/**
 * The one {@code embedding_index} row: the identity of the embedding column, the target of a
 * re-embedding while the index is stale ({@code null} otherwise) and the state of the last run.
 */
public record EmbeddingIndexRecord(EmbeddingIdentity current, EmbeddingIdentity target, Run run) {

  /** The most recent re-embedding run; every field is {@code null} before the first. */
  public record Run(
      RunStatus status,
      Instant startedAt,
      UUID startedBy,
      UUID organizationId,
      Instant finishedAt,
      String message) {

    public static final Run NONE = new Run(null, null, null, null, null, null);

    public boolean running() {
      return status == RunStatus.RUNNING;
    }

    /** This run ended now with {@code endStatus}, everything about its start kept. */
    public Run ended(RunStatus endStatus, Instant at, String endMessage) {
      return new Run(endStatus, startedAt, startedBy, organizationId, at, endMessage);
    }
  }

  public enum RunStatus {
    RUNNING,
    CANCELLED,
    FAILED,
    COMPLETED
  }

  public EmbeddingIndexRecord withCurrent(EmbeddingIdentity replacement) {
    return new EmbeddingIndexRecord(replacement, target, run);
  }

  public EmbeddingIndexRecord withTarget(EmbeddingIdentity replacement) {
    return new EmbeddingIndexRecord(current, replacement, run);
  }

  public EmbeddingIndexRecord withRun(Run replacement) {
    return new EmbeddingIndexRecord(current, target, replacement);
  }
}
