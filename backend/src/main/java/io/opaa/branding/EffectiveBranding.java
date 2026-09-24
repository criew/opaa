package io.opaa.branding;

import io.opaa.api.types.ColorScheme;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The branding actually in effect (#582, #1910): every text field carries either the operator's
 * configured value or the {@link BrandingDefaults OPAA default}, never {@code null} and never
 * "unset" - a caller renders from this alone without knowing what a default is.
 *
 * <p>The images are the genuinely optional part, because "no image configured" cannot be expressed
 * as a value the way "no product name configured" can: {@link #image} is empty for every kind none
 * is configured for, and the client falls back to what it shows without one. The bytes themselves
 * are deliberately not part of this record - see {@link BrandingImage}, which only the endpoints
 * that serve them ever load.
 */
public record EffectiveBranding(
    String productName,
    String claim,
    String primaryColor,
    ColorScheme defaultColorScheme,
    Map<BrandingImageKind, ImageMetadata> images) {

  public EffectiveBranding {
    images = Map.copyOf(images);
  }

  /** What is configured for one image kind, or empty while none is. */
  public Optional<ImageMetadata> image(BrandingImageKind kind) {
    return Optional.ofNullable(images.get(kind));
  }

  /**
   * What is known about a configured image without loading its bytes.
   *
   * @param contentType the media type the server itself detected at upload time, not one the
   *     uploader declared
   * @param version short, content-derived version used as a cache-busting query parameter, so a
   *     replaced image is fetched immediately while an unchanged one stays cacheable
   */
  public record ImageMetadata(String contentType, String version, Instant updatedAt) {}
}
