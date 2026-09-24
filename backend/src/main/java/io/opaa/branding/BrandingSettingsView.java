package io.opaa.branding;

import io.opaa.api.types.ColorScheme;
import java.time.Instant;

/**
 * The stored branding settings without any image bytes - the projection {@link
 * BrandingSettingsRepository#findSettingsWithoutImages()} returns, so the request path that renders
 * every page never loads a {@code bytea} column (see that method's Javadoc).
 *
 * <p>Still the raw, unresolved state: any field may be {@code null}, meaning "never configured".
 * {@link BrandingSettingsService} turns this into an {@link EffectiveBranding}.
 */
public record BrandingSettingsView(
    String productName,
    String claim,
    String primaryColor,
    ColorScheme defaultColorScheme,
    String logoContentType,
    String logoVersion,
    Instant logoUpdatedAt,
    String loginLogoContentType,
    String loginLogoVersion,
    Instant loginLogoUpdatedAt,
    String loginBackgroundContentType,
    String loginBackgroundVersion,
    Instant loginBackgroundUpdatedAt) {

  /**
   * One image's metadata, without its bytes. {@code version} is what decides whether an image is
   * configured at all: it is derived from the content, so it exists exactly when content does.
   */
  public record StoredImage(String contentType, String version, Instant updatedAt) {

    public boolean isPresent() {
      return version != null;
    }
  }

  StoredImage image(BrandingImageKind kind) {
    return switch (kind) {
      case LOGO -> new StoredImage(logoContentType, logoVersion, logoUpdatedAt);
      case LOGIN_LOGO ->
          new StoredImage(loginLogoContentType, loginLogoVersion, loginLogoUpdatedAt);
      case LOGIN_BACKGROUND ->
          new StoredImage(
              loginBackgroundContentType, loginBackgroundVersion, loginBackgroundUpdatedAt);
    };
  }
}
