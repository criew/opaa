package io.opaa.api.types;

/**
 * The graded asset role an {@code AssetGrant} carries - a ranking deliberately separate from {@link
 * SpaceRole} (docs/features/spaces-and-assets.md#asset-rollen). No role name occurs in both role
 * systems: the managing asset role is {@link #MANAGER}, not {@code ADMIN}, so "I have admin rights
 * here" always refers to a space and "I have manager/owner rights" always refers to an asset.
 *
 * <p>Declared in ascending order so {@link #atLeast(AssetRole)} can compare via {@link #ordinal()}.
 *
 * <p>There is no rank below {@link #VIEWER} that allows using an asset without seeing its
 * configuration: for an agent that is not enforceable, and for a library cited answers expose the
 * document titles anyway.
 *
 * <p>Exception for a knowledge library: its source connection detail - internal server paths,
 * source URLs and proxy hosts, not merely "configuration" - stays hidden from a VIEWER and even an
 * EDITOR, matching the {@link #MANAGER} bar {@code KnowledgeLibraryService#updateLibrary} already
 * enforces for changing it.
 */
public enum AssetRole {
  /** Use the asset (query a library, invoke an agent) and see its configuration. */
  VIEWER,

  /** Additionally change the configuration. */
  EDITOR,

  /** Additionally share and grant roles to others. */
  MANAGER,

  /** Additionally delete the asset and transfer ownership. */
  OWNER;

  /** Whether this role is at least as privileged as {@code other}, per the declared ordering. */
  public boolean atLeast(AssetRole other) {
    return this.ordinal() >= other.ordinal();
  }
}
