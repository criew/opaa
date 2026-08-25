package io.opaa.api.types;

/**
 * Discoverability of a space, independent of membership and asset access.
 *
 * <ul>
 *   <li>{@link #PRIVATE} - only members know it exists; mandatory for personal spaces, default for
 *       project spaces
 *   <li>{@link #DISCOVERABLE} - visible in the space directory, joining requires a request
 *   <li>{@link #OPEN} - visible in the space directory, self-service one-click join
 * </ul>
 */
public enum SpaceVisibility {
  PRIVATE,
  DISCOVERABLE,
  OPEN
}
