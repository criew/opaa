package io.opaa.branding;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the singleton {@link BrandingSettings} row (#582).
 *
 * <p><b>No read path loads a {@code bytea} column it does not serve.</b> The three image columns
 * together are up to three megabytes, and both read paths are reachable without a session and
 * without a rate limit (see {@code BrandingController}), so a request must never pull bytes it
 * throws away: {@link #findSettingsWithoutImages()} - on the render path of every page - selects
 * the metadata columns alone, and each of the three {@code find*Image} projections loads exactly
 * the one image its endpoint is about to write out. {@link #findSingleton()} loads everything and
 * is reserved for the {@code SYSTEM_ADMIN} write paths, which have to read the row anyway.
 */
@Repository
public interface BrandingSettingsRepository extends JpaRepository<BrandingSettings, Integer> {

  /** The full row including every image's bytes. For the {@code SYSTEM_ADMIN} write paths. */
  default Optional<BrandingSettings> findSingleton() {
    return findById(BrandingSettings.SINGLETON_ID);
  }

  /** One image's bytes and the two fields served with them - never the other two images'. */
  default Optional<BrandingImage> findImage(BrandingImageKind kind) {
    return switch (kind) {
      case LOGO -> findLogoImage();
      case LOGIN_LOGO -> findLoginLogoImage();
      case LOGIN_BACKGROUND -> findLoginBackgroundImage();
    };
  }

  @Query(
      "SELECT new io.opaa.branding.BrandingImage("
          + "b.logoContent, b.logoContentType, b.logoVersion)"
          + " FROM BrandingSettings b WHERE b.id = 1 AND b.logoContent IS NOT NULL")
  Optional<BrandingImage> findLogoImage();

  @Query(
      "SELECT new io.opaa.branding.BrandingImage("
          + "b.loginLogoContent, b.loginLogoContentType, b.loginLogoVersion)"
          + " FROM BrandingSettings b WHERE b.id = 1 AND b.loginLogoContent IS NOT NULL")
  Optional<BrandingImage> findLoginLogoImage();

  @Query(
      "SELECT new io.opaa.branding.BrandingImage("
          + "b.loginBackgroundContent, b.loginBackgroundContentType, b.loginBackgroundVersion)"
          + " FROM BrandingSettings b WHERE b.id = 1 AND b.loginBackgroundContent IS NOT NULL")
  Optional<BrandingImage> findLoginBackgroundImage();

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
