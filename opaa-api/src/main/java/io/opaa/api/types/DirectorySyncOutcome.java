package io.opaa.api.types;

/** What a directory synchronisation run did or did not do. See {@link DirectorySyncService}. */
public enum DirectorySyncOutcome {

  /** Changes were computed and written. Only possible for {@code run}, never for {@code dryRun}. */
  APPLIED,

  /** Nothing was written; the report shows what {@code run} would change. */
  DRY_RUN,

  /**
   * The run would have removed more than the configured fraction of a group's memberships (see
   * {@link DirectorySyncProperties#changeThresholdFraction}). Nothing was written; the report shows
   * what was rejected.
   */
  ABORTED_THRESHOLD,

  /**
   * The directory answered but reported zero groups while ORG_UNIT groups already exist - the
   * classic symptom of a misconfigured connection (e.g. after a certificate rotation), not a real
   * reorganisation. Nothing was written.
   */
  ABORTED_EMPTY_RESULT,

  /**
   * No trusted provider (the default provider in the {@code oidc} mode) exists to resolve the
   * directory's subjects among; resolving nobody would read as "remove every membership", so the
   * run stops before planning (ADR-0025, Entscheidung 4).
   */
  ABORTED_NO_TRUSTED_PROVIDER,

  /** The directory could not be reached. Nothing was written; the last-known-good state stands. */
  UNREACHABLE
}
