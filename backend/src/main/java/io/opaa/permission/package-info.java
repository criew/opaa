/**
 * The permission model itself: the rights subject ({@link io.opaa.permission.PermissionSubject}),
 * the grant of a role on an asset ({@link io.opaa.permission.AssetGrant}), the derivation "why do I
 * reach this" ({@link io.opaa.permission.GroupMembershipResolver}, {@link
 * io.opaa.permission.AssetAccessService}) and the rights history ({@link
 * io.opaa.permission.PermissionHistoryService}) - ADR-0036, Entscheidung 12.
 *
 * <p><b>This package depends on no business package.</b> {@code io.opaa.library}, {@code
 * io.opaa.group} and {@code io.opaa.space} depend on it, never the other way round; the mutual
 * dependency {@code library} &harr; {@code group} that carried the permission model before is
 * resolved by this direction. What this package needs from a business package it declares itself as
 * a port and lets that package implement: {@link io.opaa.permission.GroupMembershipSource}, {@link
 * io.opaa.permission.GroupSubjectDirectory}, {@link io.opaa.permission.AssetOwnershipDirectory}.
 * {@code io.opaa.permission.PermissionPackageBoundaryTest} holds the direction.
 *
 * <p><b>Grants are type-independent.</b> A grant names its asset by {@link
 * io.opaa.permission.AssetType} plus id, never by a column of one asset table, so a second asset
 * type needs no second rights logic (#1726). The values of {@link io.opaa.permission.AssetType}
 * belong to the business package that owns the asset - {@code KnowledgeLibrary.ASSET_TYPE} is the
 * first one; this package never enumerates them.
 *
 * <p><b>What a further asset type owes in return.</b> {@code asset_grants.asset_id} carries no
 * foreign key - it cannot, since it names an asset of any type - so the guarantee "no grant
 * outlives its asset" is held per asset type by a delete trigger on that type's own table ({@code
 * trg_knowledge_libraries_delete_asset_grants} is the first one). A new asset type brings its own;
 * that trigger is the only place a new type touches the database at all. Existence and organization
 * of the referenced asset are held by the owning service, which loads the asset before any grant is
 * written. See {@code changes/038-asset-grants-type-independent.yaml}.
 *
 * <p><b>What deliberately stays outside.</b> Everything that only holds for one asset type: the
 * role a library's organization-wide visibility grants, the guards of {@code AssetGrantService}
 * around a library's last owner, and the visibility history of a library ({@code
 * io.opaa.library.LibraryVisibilityHistoryService}). Those compose this package's formula with
 * their own type-specific part, the same way {@code io.opaa.library.LibraryAccessService} composes
 * {@link io.opaa.permission.AssetAccessService} with the visibility floor.
 */
package io.opaa.permission;
