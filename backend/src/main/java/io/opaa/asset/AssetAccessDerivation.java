package io.opaa.asset;

import io.opaa.api.types.AssetRole;
import io.opaa.permission.AccessPath;
import io.opaa.permission.AssetType;
import java.util.List;
import java.util.UUID;

/**
 * Why one person reaches one asset (#1822, ADR-0036 Entscheidung 9): the effective role and every
 * own way to it. Always about the asking person, so nothing here is ever withheld.
 */
public record AssetAccessDerivation(
    AssetType assetType, UUID assetId, AssetRole effectiveRole, List<AccessPath> paths) {}
