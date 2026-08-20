package io.opaa.branding;

import java.time.Instant;

/**
 * The stored branding settings without the logo bytes - the projection {@link
 * BrandingSettingsRepository#findSettingsWithoutLogo()} returns, so the request path that renders
 * every page never loads the {@code bytea} column (see that method's Javadoc).
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
    Instant logoUpdatedAt) {}
