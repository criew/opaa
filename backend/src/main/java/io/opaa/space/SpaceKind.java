package io.opaa.space;

/**
 * What a space is for and who may create it.
 *
 * <ul>
 *   <li>{@link #PERSONAL} - created automatically, exactly one per user
 *   <li>{@link #PROJECT} - self-service, created by any user for their own initiatives
 *   <li>{@link #TEAM} - created only by a system administrator for teams, departments or
 *       organization-wide rooms
 * </ul>
 */
public enum SpaceKind {
  PERSONAL,
  PROJECT,
  TEAM
}
