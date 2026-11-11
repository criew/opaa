package io.opaa.agent;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.asset.ResponsibleUnit;
import java.util.UUID;

/**
 * A request to create an agent: name, purpose and the responsible unit; everything else starts
 * empty.
 *
 * @param ownerType {@code null} means the creator owns it.
 * @param ownerId the owning group for {@link AssetOwnerType#GROUP}, ignored otherwise.
 * @param responsible required; without it the agent is not created
 */
public record AgentCreation(
    String name,
    String description,
    AssetOwnerType ownerType,
    UUID ownerId,
    ResponsibleUnit responsible) {}
