package io.opaa.space;

/**
 * Parameters for updating a space's mutable details (replaces the generated {@code
 * SpaceUpdateRequest} at the {@link SpaceService#updateSpace} boundary, ADR-0006/#860).
 */
public record SpaceUpdate(String name, String description, SpaceVisibility visibility) {}
