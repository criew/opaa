package io.opaa.template;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.asset.ResponsibleUnit;
import java.util.UUID;

/**
 * A request to create a document template.
 *
 * @param ownerType {@code null} means the creator owns it.
 * @param ownerId the owning group for {@link AssetOwnerType#GROUP}, ignored otherwise.
 * @param responsible the responsible unit, required
 */
public record DocumentTemplateCreation(
    String name,
    String description,
    AssetOwnerType ownerType,
    UUID ownerId,
    DocumentTemplateUpload file,
    ResponsibleUnit responsible) {}
