package io.opaa.space;

/**
 * Parameters for updating a space's mutable details - domain counterpart of the generated {@code
 * SpaceUpdateRequest} at the {@link SpaceService#updateSpace} boundary. A {@code null} {@code
 * chatAutoCleanup} leaves that setting unchanged.
 */
public record SpaceUpdate(String name, String description, Boolean chatAutoCleanup) {

  public SpaceUpdate(String name, String description) {
    this(name, description, null);
  }
}
