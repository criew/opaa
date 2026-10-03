package io.opaa.indexing.source;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

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
   * At least one scope could not be listed - named in {@code unlistedScopeKeys}, empty when the
   * source has no containers to name: no reconciliation, the assessment is recorded as incomplete.
   */
  record Incomplete(List<String> unlistedScopeKeys) implements ListingOutcome {
    public Incomplete {
      unlistedScopeKeys = List.copyOf(unlistedScopeKeys);
    }
  }

  /**
   * Everything was listed except {@code unreadableScopes} areas the source could not read: the
   * frame keeps every known document whose key {@code retained} matches, reconciles the rest and
   * records an incomplete assessment with the number of unreadable areas.
   */
  record CompleteExcept(int unreadableScopes, Predicate<String> retained)
      implements ListingOutcome {
    public CompleteExcept {
      if (unreadableScopes < 1) {
        throw new IllegalArgumentException("unreadableScopes must be positive");
      }
      Objects.requireNonNull(retained, "retained");
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

  static ListingOutcome incomplete(List<String> unlistedScopeKeys) {
    return new Incomplete(unlistedScopeKeys);
  }

  static ListingOutcome completeExcept(int unreadableScopes, Predicate<String> retained) {
    return new CompleteExcept(unreadableScopes, retained);
  }

  static ListingOutcome truncated() {
    return new Truncated();
  }

  static ListingOutcome partial() {
    return new Partial();
  }
}
