package io.opaa.asset;

import io.opaa.permission.SuccessionFinding;

/**
 * One entry of the catalog as the caller sees it.
 *
 * @param accessible whether the rights formula lets the caller read the asset; {@code false} for an
 *     asset that is only listed.
 * @param ownerLabel {@code null} when the owner has no name the caller may see.
 * @param succession {@code null} while the asset has a capable owner.
 * @param itemCount what the asset holds - documents, prompts - by its type's count.
 * @param spaceCount in how many spaces the asset is associated.
 */
public record AssetCatalogEntry(
    AssetCatalogRow asset,
    boolean accessible,
    String ownerLabel,
    SuccessionFinding succession,
    long itemCount,
    long spaceCount) {}
