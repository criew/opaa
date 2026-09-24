/**
 * The permission model itself: the rights subject ({@link io.opaa.permission.PermissionSubject}),
 * the grant of a role on an asset ({@link io.opaa.permission.AssetGrant}), the rights formula and
 * its Herleitung ({@link io.opaa.permission.GroupMembershipResolver}, {@link
 * io.opaa.permission.AssetAccessService}) and the rights history ({@link
 * io.opaa.permission.PermissionHistoryService}) - ADR-0036, Entscheidung 12.
 *
 * <p><b>This package depends on no business package, and not on the asset shell.</b> {@code
 * io.opaa.asset}, {@code io.opaa.library}, {@code io.opaa.group} and {@code io.opaa.space} depend
 * on it, never the other way round. What it needs from one of them it declares itself as a port and
 * lets that package implement: {@link io.opaa.permission.GroupMembershipSource}, {@link
 * io.opaa.permission.GroupSubjectDirectory}, {@link io.opaa.permission.AssetOwnershipDirectory}.
 * {@code io.opaa.permission.PermissionPackageBoundaryTest} holds the direction.
 *
 * <p><b>Grants are type-independent.</b> A grant names its asset by {@link
 * io.opaa.permission.AssetType} plus id; the values of {@code AssetType} belong to the packages
 * that own the asset types, and this package never enumerates them. {@code asset_grants} points at
 * the asset shell ({@code fk_asset_grants_asset_organization}, changeset 081), so "no grant
 * outlives its asset" holds in the database for every type at once.
 *
 * <p><b>The formula is complete here.</b> Direct grant, group grant and the organization-wide
 * release ({@code assets.visibility}) together decide what a person may read; the only floor
 * outside it is the asset administration's, where a system administrator counts as owner - never
 * for the search.
 */
package io.opaa.permission;
