package io.opaa.api.types;

/**
 * Who a {@link Capability} grant refers to. Deliberately its own enum next to {@link
 * PermissionSubjectType} rather than a third value on it: {@code ALL_ACCOUNTS} exists only for
 * capabilities, and adding it to the shared type would let a caller aim an asset grant at a subject
 * the grant tables neither store nor check (ADR-0036, Entscheidung 5).
 */
public enum CapabilitySubjectType {
  USER,
  GROUP,

  /**
   * Every account of the organization, without naming one - the delivered state of the three
   * creation capabilities. Not a synthetic group object: one would appear in every group picker and
   * duplicate {@code visibility = ORGANIZATION}.
   */
  ALL_ACCOUNTS
}
