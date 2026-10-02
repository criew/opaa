package io.opaa.space;

import io.opaa.api.types.SpaceVisibility;

/**
 * Parameters for updating a space's mutable details - domain counterpart of the generated {@code
 * SpaceUpdateRequest} at the {@link SpaceService#updateSpace} boundary. A {@code null} {@code
 * visibility} or {@code chatAutoCleanup} leaves that setting unchanged.
 */
public record SpaceUpdate(
    String name, String description, SpaceVisibility visibility, Boolean chatAutoCleanup) {

  public SpaceUpdate(String name, String description, SpaceVisibility visibility) {
    this(name, description, visibility, null);
  }
}
