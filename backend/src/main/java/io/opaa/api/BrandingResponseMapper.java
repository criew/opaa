package io.opaa.api;

import io.opaa.api.dto.BrandingResponse;
import io.opaa.branding.EffectiveBranding;

/**
 * Maps the domain's {@link EffectiveBranding} onto the generated {@link BrandingResponse}
 * (ADR-0006: API DTOs are generated from the specification, never hand-written). Shared by {@link
 * BrandingController} and {@link SystemBrandingController} - both answer with the same
 * representation, and a second copy of this mapping is exactly how the two would drift apart.
 */
final class BrandingResponseMapper {

  private BrandingResponseMapper() {}

  static BrandingResponse toResponse(EffectiveBranding branding) {
    BrandingResponse response =
        new BrandingResponse(
            branding.productName(),
            branding.claim(),
            branding.primaryColor(),
            branding.defaultColorScheme());
    branding
        .logo()
        .ifPresent(
            logo -> {
              // The content-derived version turns "the logo changed" into "a different URL", which
              // is what lets BrandingController#getBrandingLogo cache aggressively without ever
              // serving a stale logo.
              response.setLogoUrl("/api/v1/branding/logo?v=" + logo.version());
              response.setLogoContentType(logo.contentType());
              response.setLogoUpdatedAt(logo.updatedAt());
            });
    return response;
  }
}
