package io.opaa.api.types;

/**
 * Who a {@link Capability} grant refers to. Its own enum next to {@link PermissionSubjectType}
 * rather than a third value on it: that one carries space memberships and ownership, where a
 * subject "everyone" is a state the model does not know (ADR-0036, Entscheidung 5; ADR-0037,
 * Entscheidung 3). {@link AssetGrantSubjectType} makes the same cut for asset grants.
 */
public enum CapabilitySubjectType {
  USER,
  GROUP,

  /**
   * Every account of the organization, without naming one - the delivered state of the three
   * creation capabilities. Not a synthetic group object: one would appear in every group picker and
   * would have to be kept out of the directory sync, the stewardship and the transfer one by one.
   */
  ALL_ACCOUNTS
}
