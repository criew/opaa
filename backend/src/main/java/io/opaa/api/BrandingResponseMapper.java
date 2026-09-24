package io.opaa.api;

import io.opaa.api.dto.BrandingResponse;
import io.opaa.branding.BrandingImageKind;
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
        .image(BrandingImageKind.LOGO)
        .ifPresent(
            logo -> {
              // The content-derived version turns "the image changed" into "a different URL", which
              // is what lets BrandingController cache aggressively without ever serving a stale
              // one.
              response.setLogoUrl(url("logo", logo.version()));
              response.setLogoContentType(logo.contentType());
              response.setLogoUpdatedAt(logo.updatedAt());
            });
    branding
        .image(BrandingImageKind.LOGIN_LOGO)
        .ifPresent(
            loginLogo -> {
              response.setLoginLogoUrl(url("login-logo", loginLogo.version()));
              response.setLoginLogoContentType(loginLogo.contentType());
              response.setLoginLogoUpdatedAt(loginLogo.updatedAt());
            });
    branding
        .image(BrandingImageKind.LOGIN_BACKGROUND)
        .ifPresent(
            background -> {
              response.setLoginBackgroundUrl(url("login-background", background.version()));
              response.setLoginBackgroundContentType(background.contentType());
              response.setLoginBackgroundUpdatedAt(background.updatedAt());
            });
    return response;
  }

  private static String url(String path, String version) {
    return "/api/v1/branding/" + path + "?v=" + version;
  }
}
