package io.opaa.api.types;

/**
 * One kind of effect a transfer can move from one subject to another (ADR-0036, Entscheidung 10).
 * The caller picks a subset, so "nur Grants" or "nur Eigentum" is one operation and not a second
 * one.
 *
 * <p>Which parts a pair of subjects admits depends on that pair: {@link #ASSET_GRANTS}, {@link
 * #SPACE_MEMBERSHIPS} and {@link #CAPABILITIES} are group-to-group only, {@link #STEWARDSHIP} is
 * person-to-person only - a group is never a steward, and the effects of a person are neither
 * transferable nor listable (ADR-0036, Entscheidung 10).
 */
public enum PermissionTransferScope {

  /** Every {@code AssetGrant} the source holds, on every asset type. */
  ASSET_GRANTS,

  /** Every space the source is a member of, with its role. */
  SPACE_MEMBERSHIPS,

  /** Every capability ("Anlegerecht") granted to the source. */
  CAPABILITIES,

  /** Every asset owned by the source - libraries, and for a person also spaces. */
  OWNERSHIP,

  /** Every internal group the source is responsible for. */
  STEWARDSHIP
}
