package io.opaa.api;

import io.opaa.api.dto.AccessPathGroup;
import io.opaa.api.dto.AccessPathResponse;
import io.opaa.api.dto.AssetAccessDerivationResponse;
import io.opaa.api.dto.SpaceAccessDerivationResponse;
import io.opaa.asset.AssetAccessDerivation;
import io.opaa.permission.AccessPath;
import io.opaa.permission.GroupAttribution;
import io.opaa.space.SpaceAccessDerivation;
import java.util.List;

/**
 * Maps the Herleitung of an asset and of a space onto their generated responses (#1822, ADR-0006).
 * Pure: whether a way is withheld is decided in the domain service, not here.
 */
final class AccessDerivationResponseMapper {

  private AccessDerivationResponseMapper() {}

  static AssetAccessDerivationResponse toResponse(AssetAccessDerivation derivation) {
    // pathsWithheld is constantly false: the asset derivation is only ever about the asking
    // person, and an own derivation is never withheld (ADR-0036, Entscheidung 9, Personalrat Z2).
    return new AssetAccessDerivationResponse(
        io.opaa.api.dto.AssetType.fromValue(derivation.assetType().value()),
        derivation.assetId(),
        derivation.effectiveRole(),
        toPaths(derivation.paths()),
        false);
  }

  static SpaceAccessDerivationResponse toResponse(SpaceAccessDerivation derivation) {
    return new SpaceAccessDerivationResponse(
            derivation.spaceId(),
            derivation.userId(),
            toPaths(derivation.paths()),
            derivation.pathsWithheld())
        .effectiveRole(derivation.effectiveRole());
  }

  private static List<AccessPathResponse> toPaths(List<AccessPath> paths) {
    return paths.stream().map(AccessDerivationResponseMapper::toPath).toList();
  }

  private static AccessPathResponse toPath(AccessPath path) {
    return new AccessPathResponse(path.basis())
        .assetRole(path.assetRole())
        .spaceRole(path.spaceRole())
        .since(path.since())
        .group(toGroup(path.group()));
  }

  private static AccessPathGroup toGroup(GroupAttribution group) {
    return group == null
        ? null
        : new AccessPathGroup(group.id(), group.name(), group.origin(), group.mechanism())
            .providerName(group.providerName());
  }
}
