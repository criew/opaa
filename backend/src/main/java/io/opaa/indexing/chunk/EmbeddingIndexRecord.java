package io.opaa.indexing.chunk;

import java.time.Instant;
import java.util.UUID;

/**
 * The one {@code embedding_index} row: the identity of the embedding column, the target of a
 * re-embedding while the index is stale ({@code null} otherwise) and the state of the last run. The
 * generations of the two columns are never equal (ADR-0061, Entscheidung 2).
 */
public record EmbeddingIndexRecord(EmbeddingIdentity current, EmbeddingIdentity target, Run run) {

  /** What a run works on (ADR-0061, Entscheidung 4). */
  public enum RunKind {
    /** Fills the target column under the target generation, then switches. */
    REEMBED,
    /** Fills the embedding column after a discard, under its generation; never switches. */
    REFILL
  }

  /**
   * The most recent run; every field is {@code null} before the first. {@code kind} and {@code
   * generation} name what it works on; a worker works only while they and the owner are its own.
   * {@code indexBuildStartedAt} is set while it builds the index of the target column.
   */
  public record Run(
      RunStatus status,
      Instant startedAt,
      UUID startedBy,
      UUID organizationId,
      Instant finishedAt,
      String message,
      RunKind kind,
      Long generation,
      Instant indexBuildStartedAt) {

    public static final Run NONE = new Run(null, null, null, null, null, null, null, null, null);

    public boolean running() {
      return status == RunStatus.RUNNING;
    }

    /** This run ended now with {@code endStatus}, everything about its start kept. */
    public Run ended(RunStatus endStatus, Instant at, String endMessage) {
      return new Run(
          endStatus, startedAt, startedBy, organizationId, at, endMessage, kind, generation, null);
    }

    /** {@link #kind}, a run recorded before kinds existed being a re-embedding. */
    public RunKind kindOrReembed() {
      return kind == null ? RunKind.REEMBED : kind;
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
