package io.opaa.branding;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the singleton {@link BrandingSettings} row (#582).
 *
 * <p>{@link #findSettingsWithoutImages()} exists because {@code GET /api/v1/branding} is on the
 * render path of every page and runs for every signed-in user, while the three image {@code bytea}
 * columns together are up to three megabytes: a plain {@code findById} would pull those bytes into
 * memory on every one of those requests only to throw them away. The projection selects the
 * metadata columns alone; {@link #findSingleton()} - which does load the bytes - is reserved for
 * the endpoints that actually serve them and for writes.
 */
@Repository
public interface BrandingSettingsRepository extends JpaRepository<BrandingSettings, Integer> {

  /** The full row including every image's bytes. For the image-serving endpoints and for writes. */
  default Optional<BrandingSettings> findSingleton() {
    return findById(BrandingSettings.SINGLETON_ID);
  }

  /** Everything except the image bytes - see the interface Javadoc for why that matters. */
  @Query(
      "SELECT new io.opaa.branding.BrandingSettingsView("
          + "b.productName, b.claim, b.primaryColor, b.defaultColorScheme,"
          + " b.logoContentType, b.logoVersion, b.logoUpdatedAt,"
          + " b.loginLogoContentType, b.loginLogoVersion, b.loginLogoUpdatedAt,"
          + " b.loginBackgroundContentType, b.loginBackgroundVersion, b.loginBackgroundUpdatedAt)"
          + " FROM BrandingSettings b WHERE b.id = 1")
  Optional<BrandingSettingsView> findSettingsWithoutImages();
}
