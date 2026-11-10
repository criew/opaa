package io.opaa.agent;

import io.opaa.api.types.AssetOwnerType;
import java.util.UUID;

/**
 * A request to create an agent: name and purpose only, everything else starts empty.
 *
 * @param ownerType {@code null} means the creator owns it.
 * @param ownerId the owning group for {@link AssetOwnerType#GROUP}, ignored otherwise.
 */
public record AgentCreation(
    String name, String description, AssetOwnerType ownerType, UUID ownerId) {}
