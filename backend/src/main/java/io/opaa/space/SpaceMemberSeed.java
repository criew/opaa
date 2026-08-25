package io.opaa.space;

import io.opaa.api.types.SpaceRole;
import java.util.UUID;

/** One member to add when a space is created - the domain counterpart of the generated request. */
public record SpaceMemberSeed(UUID userId, SpaceRole role) {}
