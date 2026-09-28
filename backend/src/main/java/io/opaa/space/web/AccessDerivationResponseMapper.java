package io.opaa.space.web;

import io.opaa.api.dto.SpaceAccessDerivationResponse;
import io.opaa.permission.web.AccessPathResponseMapper;
import io.opaa.space.SpaceAccessDerivation;

/** Maps the Herleitung of a space onto its generated response (#1822, ADR-0006). */
final class AccessDerivationResponseMapper {

  private AccessDerivationResponseMapper() {}

  static SpaceAccessDerivationResponse toResponse(SpaceAccessDerivation derivation) {
    return new SpaceAccessDerivationResponse(
            derivation.spaceId(),
            derivation.userId(),
            AccessPathResponseMapper.toResponses(derivation.paths()),
            derivation.pathsWithheld())
        .effectiveRole(derivation.effectiveRole());
  }
}
