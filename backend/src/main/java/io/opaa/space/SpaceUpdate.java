package io.opaa.space;

/**
 * Parameters for updating a space's mutable details - domain counterpart of the generated {@code
 * SpaceUpdateRequest} at the {@link SpaceService#updateSpace} boundary.
 */
public record SpaceUpdate(String name, String description, SpaceVisibility visibility) {}
