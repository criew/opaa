/**
 * The asset shell (#1899, ADR-0036 Entscheidung 12, Nachtrag vom 24.09.2026): what every asset has,
 * whatever its type - the table {@code assets} with {@link io.opaa.asset.Asset} as the root of a
 * {@code JOINED} hierarchy, the grants on it ({@link io.opaa.asset.AssetGrantService}), the
 * Herleitung ({@link io.opaa.asset.AssetAccessDerivationService}), its release level, findability
 * and owner ({@link io.opaa.asset.AssetShellService}), the reach history ({@link
 * io.opaa.asset.AssetVisibilityHistoryService}), succession ({@link
 * io.opaa.asset.AssetSuccessionSource}) and ownership transfer. There is exactly one of each; an
 * asset type adds a table, an entity extending {@code Asset} and an {@link
 * io.opaa.asset.AssetTypeDefinition} - no grant logic, no history, no finding source of its own.
 *
 * <p><b>Dependencies.</b> This package builds on {@code io.opaa.permission} (the rights formula,
 * the grant rows, the rights history) and never reaches a business package: {@code io.opaa.library}
 * and {@code io.opaa.space} depend on it, not the other way round, and {@code io.opaa.permission}
 * does not depend on it either. {@code io.opaa.permission.PermissionPackageBoundaryTest} holds the
 * direction.
 *
 * <p><b>Foreign keys.</b> The live tables that name an asset - {@code asset_grants} and {@code
 * space_asset_associations} - point at {@code assets} with the organization in the key and {@code
 * ON DELETE CASCADE}. The history tables do not (ADR-0016): a history outlives its asset, and the
 * shell closes the open intervals itself before an asset is deleted ({@link
 * io.opaa.asset.AssetShellService#registerDeleted}).
 */
package io.opaa.asset;
