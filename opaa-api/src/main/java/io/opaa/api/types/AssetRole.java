package io.opaa.api.types;

/**
 * The graded asset role an {@link AssetGrant} carries - a ranking deliberately separate from {@code
 * io.opaa.space.SpaceRole} (see #202, docs/features/spaces-and-assets.md#asset-rollen). No role
 * name occurs in both role systems: the managing asset role is {@link #MANAGER}, not {@code ADMIN},
 * so "I have admin rights here" always refers to a space and "I have manager/owner rights" always
 * refers to an asset.
 *
 * <p>Declared in ascending order so {@link #atLeast(AssetRole)} can compare via {@link #ordinal()}.
 *
 * <p>An earlier draft placed a {@code USER} rank below {@link #VIEWER} that allowed using an asset
 * without seeing its configuration. It was dropped in #330: for an agent the guarantee is not
 * enforceable, because whoever may invoke it can ask it for its own instructions, and for a library
 * it runs largely empty, because cited answers expose the document titles anyway.
 *
 * <p>Exception for a knowledge library (#507): its source connection detail - internal server
 * paths, source URLs and proxy hosts, not merely "configuration" in the #330 sense above - stays
 * hidden from a VIEWER and even an EDITOR, matching the {@link #MANAGER} bar {@code
 * KnowledgeLibraryService#updateLibrary} already enforces for changing it.
 */
public enum AssetRole {
  /** Use the asset (query a library, invoke an agent) and see its configuration. */
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
