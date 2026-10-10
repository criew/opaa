package io.opaa.indexing.source;

/**
 * Why a run failed or ended early, where the frame tells the reason apart from the German message;
 * the job stores it ({@code indexing_jobs.failure_category}). A blocked source ends under its
 * block's reason, one constant per {@link SourceBlock.Reason} of the same name. No category refers
 * to content.
 */
public enum RunFailureCategory {
  TYPE_LOCKED,
  PROFILE_LOCKED,
  PROFILE_REQUIRED,
  ACCESS_REMOVED,
  OWNER_DEACTIVATED,
  DORMANT,
  TARGET_OUTSIDE_PROFILE,
  NOT_CONNECTED,
  EXPIRED,
  /** The source rejected the secret, also once the core was asked again ("Anmeldung abgelehnt"). */
  CREDENTIALS_REJECTED,
  /**
   * The run rejected an item or attachment at a storage quota - its library's, or for a private
   * library the one across all private libraries of the owner; it went on to its end and is
   * completed as incomplete, not failed.
   */
  QUOTA_EXHAUSTED;

  /** The category of a run the block for {@code reason} ended. */
  public static RunFailureCategory of(SourceBlock.Reason reason) {
    return valueOf(reason.name());
  }
}
