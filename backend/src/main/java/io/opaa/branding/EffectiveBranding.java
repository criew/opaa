package io.opaa.branding;

import io.opaa.api.types.ColorScheme;
import java.time.Instant;
import java.util.Optional;

/**
 * The branding actually in effect (#582): every field carries either the operator's configured
 * value or the {@link BrandingDefaults OPAA default}, never {@code null} and never "unset" - a
 * caller renders from this alone without knowing what a default is.
 *
 * <p>The logo is the one genuinely optional part, because "no logo configured" cannot be expressed
 * as a value the way "no product name configured" can: {@link #logo()} is empty while none is
 * configured, and the client falls back to the bundled OPAA logo. The bytes themselves are
 * deliberately not part of this record - see {@link BrandingLogo}, which only the endpoint that
 * serves them ever loads.
 */
public record EffectiveBranding(
    String productName,
    String claim,
    String primaryColor,
    ColorScheme defaultColorScheme,
    Optional<LogoMetadata> logo) {

  /**
   * What is known about a configured logo without loading its bytes.
   *
   * @param contentType the media type the server itself detected when the logo was uploaded, not
   *     one the uploader declared
   * @param version short, content-derived version used as a cache-busting query parameter, so a
   *     replaced logo is fetched immediately while an unchanged one stays cacheable
   */
  public record LogoMetadata(String contentType, String version, Instant updatedAt) {}
}
