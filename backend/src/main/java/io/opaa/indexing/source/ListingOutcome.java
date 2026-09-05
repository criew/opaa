package io.opaa.indexing.source;

import java.util.List;

/**
 * How much of its source a run's enumeration covered - what the run frame needs to decide whether
 * absence is evidence (reconciliation), what to persist as the run's listing assessment, and
 * whether the run's cost is marked incomplete.
 */
public sealed interface ListingOutcome {

  /**
   * Every item of the source was listed: the frame reconciles and records a complete assessment.
   */
  record Complete() implements ListingOutcome {}

  /**
   * At least one container could not be listed - named in {@code unreadableContainerKeys}, empty
   * when the source has no containers to name: no reconciliation, the assessment is recorded as
   * incomplete.
   */
  record Incomplete(List<String> unreadableContainerKeys) implements ListingOutcome {
    public Incomplete {
      unreadableContainerKeys = List.copyOf(unreadableContainerKeys);
    }
  }

  /**
   * The run stopped in an orderly way before covering everything and the next run continues where
   * it left off (a spent request budget): no reconciliation, no assessment, the cost is marked
   * incomplete.
   */
  record Truncated() implements ListingOutcome {}

  /**
   * The run never meant to list the source completely ("ergänzend"): nothing to reconcile or
   * assess. Only valid for a run mode whose policy is {@link
   * VanishedDocumentPolicy#KEEP_ON_ABSENCE}.
   */
  record Partial() implements ListingOutcome {}

  static ListingOutcome complete() {
    return new Complete();
  }

  static ListingOutcome incomplete(List<String> unreadableContainerKeys) {
    return new Incomplete(unreadableContainerKeys);
  }

  static ListingOutcome truncated() {
    return new Truncated();
  }

  static ListingOutcome partial() {
    return new Partial();
  }
}
