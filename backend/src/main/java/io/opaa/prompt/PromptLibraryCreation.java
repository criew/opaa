package io.opaa.prompt;

import io.opaa.api.types.AssetOwnerType;
import java.util.UUID;

/**
 * A request to create a prompt library.
 *
 * @param ownerType {@code null} means the creator owns it.
 * @param ownerId the owning group for {@link AssetOwnerType#GROUP}, ignored otherwise.
 */
public record PromptLibraryCreation(
    String name, String description, AssetOwnerType ownerType, UUID ownerId) {}
