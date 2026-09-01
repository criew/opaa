package io.opaa.searchadmin;

/**
 * How a {@link ModelRole} is doing. {@link #UNCONFIGURED} and {@link #UNREACHABLE} are the
 * Störungszustände - a role switched on but unbelegt, and one belegt but not answering; {@link
 * #isFault()} is the single place that distinction is decided, so no consumer has to enumerate the
 * constants itself.
 */
public enum ModelRoleCondition {

  /** Belegt and answering. */
  ACTIVE,

  /** Deliberately switched off - a statement, not an absence. */
  DISABLED,

  /** Expected to be belegt, but no endpoint or model is configured. */
  UNCONFIGURED,

  /** Belegt, but the endpoint did not answer. */
  UNREACHABLE;

  public boolean isFault() {
    return this == UNCONFIGURED || this == UNREACHABLE;
  }
}
