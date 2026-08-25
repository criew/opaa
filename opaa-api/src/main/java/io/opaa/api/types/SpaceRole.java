package io.opaa.api.types;

/**
 * Membership role within a space. This is a separate ranking from asset roles - no role name
 * appears in both systems, so "ADMIN" always refers to a space.
 *
 * <ul>
 *   <li>{@link #MEMBER} - enter the space; create and run chats; read all placed chats and
 *       artifacts; see curated assets, filtered to their own access
 *   <li>{@link #CURATOR} - additionally associate and detach assets, organize content
 *   <li>{@link #ADMIN} - additionally manage members and roles, set settings and the policy
 *       ceiling, withdraw placed content
 * </ul>
 *
 * <p>The space owner is tracked separately via {@code Space.ownerId}; only the owner or a system
 * administrator may delete a space or transfer that responsibility.
 *
 * <p>Declared in ascending order so {@link #atLeast(SpaceRole)} can compare via {@link #ordinal()}
 * - mirrors {@link AssetRole#atLeast}.
 */
public enum SpaceRole {
  MEMBER,
  CURATOR,
  ADMIN;

  /** Whether this role is at least as privileged as {@code other}, per the declared ordering. */
  public boolean atLeast(SpaceRole other) {
    return this.ordinal() >= other.ordinal();
  }
}
