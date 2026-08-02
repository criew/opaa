package io.opaa.space;

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
 */
public enum SpaceRole {
  MEMBER,
  CURATOR,
  ADMIN
}
