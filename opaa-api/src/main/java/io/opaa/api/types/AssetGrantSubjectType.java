package io.opaa.api.types;

/**
 * Who an {@code AssetGrant} (backend module) refers to. Its own enum next to {@link
 * PermissionSubjectType} rather than a third value on it: that one also carries space memberships
 * and ownership, and neither knows a subject "everyone" (ADR-0037, Entscheidung 3).
 */
public enum AssetGrantSubjectType {
  USER,
  GROUP,

  /**
   * Every account of the organization, without naming one - "Alle Konten". Replaces the former
   * release level {@code AssetVisibility.ORGANIZATION}: organization-wide reach is a grant like any
   * other, in the same list and the same history (#1931). Not a synthetic group object, for the
   * reason {@link CapabilitySubjectType#ALL_ACCOUNTS} already gives.
   */
  ALL_ACCOUNTS
}
