package io.opaa.audit;

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

/**
 * The result of verifying the checksum chain of one month (ADR-0059, Entscheidung 5): whether the
 * recomputed chain matches every checkpoint and the seal, how many entries the chain head covers
 * and how many lie above it, and, on a mismatch, the first segment that no longer fits. Carries no
 * content of the log.
 *
 * @param headHash the chain head as the last checkpoint stored it, hex; {@code null} without one
 * @param sealEventId the {@code AUDIT_LOG_MONTH_SEALED} entry of the seal; {@code null} unsealed
 * @param deviation the first mismatch, {@code null} unless the status is {@link Status#BROKEN}
 */
public record AuditChainReport(
    YearMonth month,
    Status status,
    boolean sealed,
    long chainedEntries,
    long unchainedEntries,
    int checkpoints,
    String headHash,
    UUID sealEventId,
    String algorithm,
    Deviation deviation) {

  public enum Status {
    /** Every checkpoint and, if present, the seal match the recomputed chain. */
    INTACT,
    /** A checkpoint or the seal does not match; {@link #deviation} names the segment. */
    BROKEN,
    /** No checkpoint yet; nothing to compare against. */
    UNCHAINED,
    /** No partition holds this month: the retention removed it, or it was never created. */
    NOT_RETAINED,
    /** The month was chained under a procedure this version does not know. */
    UNVERIFIABLE
  }

  public enum DeviationKind {
    /** The entry at the checkpoint is the one it names, but the hash differs: an entry altered. */
    CHANGED,
    /** The entry at the checkpoint's position is another one or missing. */
    INSERTED_OR_REMOVED
  }

  /**
   * The segment of the month between the previous checkpoint (or the start of the month) and the
   * first checkpoint or seal that no longer matches.
   */
  public record Deviation(
      long position, DeviationKind kind, Instant segmentFrom, Instant segmentTo) {}
}
