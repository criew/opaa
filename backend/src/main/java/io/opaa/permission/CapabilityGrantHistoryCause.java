package io.opaa.permission;

/**
 * The operation that opened or closed a {@link CapabilityGrantHistory} interval (#1813). Mirrored
 * by the database check constraint {@code chk_capability_grant_history_cause}.
 */
public enum CapabilityGrantHistoryCause {
  /**
   * The state the installation is delivered with - written by changeset 051 for every organization
   * that existed at migration time and by {@code trg_organizations_seed_capability_grants} for
   * every one created afterwards, both without an actor. Its own cause rather than {@code GRANTED}:
   * nobody decided it, and it is the longest-running state most installations will ever have.
   */
  DELIVERED,

  /** A system administrator granted the capability to this subject. */
  GRANTED,

  /** A system administrator withdrew the capability from this subject. */
  REVOKED
}
