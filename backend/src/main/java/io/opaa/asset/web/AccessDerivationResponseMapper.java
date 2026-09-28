package io.opaa.asset.web;

import io.opaa.api.dto.AssetAccessDerivationResponse;
import io.opaa.asset.AssetAccessDerivation;
import io.opaa.permission.web.AccessPathResponseMapper;

/** Maps the Herleitung of an asset onto its generated response (#1822, ADR-0006). */
final class AccessDerivationResponseMapper {

  private AccessDerivationResponseMapper() {}

  static AssetAccessDerivationResponse toResponse(AssetAccessDerivation derivation) {
    // pathsWithheld is constantly false: the asset derivation is only ever about the asking
    // person, and an own derivation is never withheld (ADR-0036, Entscheidung 9, Personalrat Z2).
    return new AssetAccessDerivationResponse(
        io.opaa.api.dto.AssetType.fromValue(derivation.assetType().value()),
        derivation.assetId(),
        derivation.effectiveRole(),
        AccessPathResponseMapper.toResponses(derivation.paths()),
        false);
  }
}
