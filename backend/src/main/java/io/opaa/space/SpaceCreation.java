package io.opaa.space;

import java.util.List;
import java.util.UUID;

/**
 * Parameters for creating a space (replaces the generated {@code SpaceRequest} at the {@link
 * SpaceService#createSpace} boundary, ADR-0006/#860). {@code ownerId} may be {@code null} - {@link
 * SpaceService#createSpace} then defaults it to the caller.
 */
public record SpaceCreation(
    String name,
    String description,
    UUID ownerId,
    SpaceVisibility visibility,
    List<SpaceMemberSeed> initialMembers,
    List<UUID> libraryIds) {}
