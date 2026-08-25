package io.opaa.space;

import io.opaa.api.types.SpaceVisibility;

/**
 * Parameters for updating a space's mutable details - domain counterpart of the generated {@code
 * SpaceUpdateRequest} at the {@link SpaceService#updateSpace} boundary.
 */
public record SpaceUpdate(String name, String description, SpaceVisibility visibility) {}
