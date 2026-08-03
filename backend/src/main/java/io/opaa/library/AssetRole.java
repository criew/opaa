package io.opaa.library;

/**
 * The graded asset role an {@link AssetGrant} carries - a ranking deliberately separate from {@code
 * io.opaa.space.SpaceRole} (see #202, docs/features/spaces-and-assets.md#asset-rollen). No role
 * name occurs in both role systems: the managing asset role is {@link #MANAGER}, not {@code ADMIN},
 * so "I have admin rights here" always refers to a space and "I have manager/owner rights" always
 * refers to an asset.
 *
 * <p>Declared in ascending order so {@link #atLeast(AssetRole)} can compare via {@link #ordinal()};
 * the separation between {@link #USER} (use without seeing configuration) and {@link #VIEWER} (see
 * configuration) is the feature's central gain - a vetted agent or library can be rolled out widely
 * without exposing its sources to every user of it.
 */
public enum AssetRole {
  /** Use the asset (query a library, invoke an agent) without seeing its configuration. */
  USER,

  /** Additionally see the configuration: description, bound libraries, document list. */
  VIEWER,

  /** Additionally change the configuration. */
  EDITOR,

  /** Additionally share, grant roles to others, and set visibility/listed. */
  MANAGER,

  /** Additionally delete the asset and transfer ownership. */
  OWNER;

  /** Whether this role is at least as privileged as {@code other}, per the declared ordering. */
  public boolean atLeast(AssetRole other) {
    return this.ordinal() >= other.ordinal();
  }
}
