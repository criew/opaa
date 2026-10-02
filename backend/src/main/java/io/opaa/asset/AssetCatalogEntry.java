package io.opaa.asset;

import io.opaa.api.types.AssetRole;
import io.opaa.permission.SuccessionFinding;

/**
 * One entry of the catalog as the caller sees it - always an asset the caller may read.
 *
 * @param myRole the caller's role by the formula, at least {@link AssetRole#VIEWER}.
 * @param facts the facts only the asset's type has; {@code null} for a type without any.
 * @param ownerLabel {@code null} when the owner has no name the caller may see.
 * @param succession {@code null} while the asset has a capable owner.
 * @param itemCount what the asset holds - documents, prompts - by its type's count.
 * @param spaceCount in how many spaces the asset is associated.
 */
public record AssetCatalogEntry(
    AssetCatalogRow asset,
    AssetRole myRole,
    AssetCatalogVisibility visibility,
    AssetCatalogStatus status,
    AssetCatalogFacts facts,
    String ownerLabel,
    SuccessionFinding succession,
    long itemCount,
    long spaceCount) {}
