package io.opaa.api.types;

/**
 * What a directory synchronisation run did or did not do. See {@code DirectorySyncService} (backend
 * module).
 */
public enum DirectorySyncOutcome {

  /** Changes were computed and written. Only possible for {@code run}, never for {@code dryRun}. */
  APPLIED,

  /** Nothing was written; the report shows what {@code run} would change. */
  DRY_RUN,

  /**
   * The run would have removed more than the configured fraction of a group's memberships (see
   * {@code DirectorySyncProperties#changeThresholdFraction} in the backend module). Nothing was
   * written; the plan is kept and waits for a system administrator to confirm or discard it
   * (ADR-0036, Entscheidung 3). A legitimate large run - a reorganisation - is applicable this way
   * instead of being permanently refused.
   */
  PENDING_CONFIRMATION,

  /**
   * The same measurement on a dry run, which by contract writes nothing at all and therefore leaves
   * no plan behind either.
   */
  ABORTED_THRESHOLD,

  /**
   * The directory answered but reported zero groups while ORG_UNIT groups already exist - the
   * classic symptom of a misconfigured connection (e.g. after a certificate rotation), not a real
   * reorganisation. Nothing was written, and this stays a hard abort: "the source did not answer
   * the way it should" is nothing anyone may click away (ADR-0036, Entscheidung 3).
   */
  ABORTED_EMPTY_RESULT,

  /** The directory could not be reached. Nothing was written; the last-known-good state stands. */
  UNREACHABLE
}
