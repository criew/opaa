package io.opaa.template;

import io.opaa.api.types.AssetOwnerType;
import java.util.UUID;

/**
 * A request to create a document template.
 *
 * @param ownerType {@code null} means the creator owns it.
 * @param ownerId the owning group for {@link AssetOwnerType#GROUP}, ignored otherwise.
 */
public record DocumentTemplateCreation(
    String name,
    String description,
    AssetOwnerType ownerType,
    UUID ownerId,
    DocumentTemplateUpload file) {}
