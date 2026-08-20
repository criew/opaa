package io.opaa.branding;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the singleton {@link BrandingSettings} row (#582).
 *
 * <p>{@link #findSettingsWithoutLogo()} exists because {@code GET /api/v1/branding} is on the
 * render path of every page and runs for every signed-in user, while the logo's {@code bytea}
 * column is up to half a megabyte: a plain {@code findById} would pull those bytes into memory on
 * every one of those requests only to throw them away. The projection selects the metadata columns
 * alone; {@link #findSingleton()} - which does load the bytes - is reserved for the one endpoint
 * that actually serves them.
 */
@Repository
public interface BrandingSettingsRepository extends JpaRepository<BrandingSettings, Integer> {

  /** The full row including the logo bytes. For the logo-serving endpoint and for writes. */
  default Optional<BrandingSettings> findSingleton() {
    return findById(BrandingSettings.SINGLETON_ID);
  }

  /** Everything except the logo bytes - see the interface Javadoc for why that matters. */
  @Query(
      "SELECT new io.opaa.branding.BrandingSettingsView("
          + "b.productName, b.claim, b.primaryColor, b.defaultColorScheme,"
          + " b.logoContentType, b.logoVersion, b.logoUpdatedAt)"
          + " FROM BrandingSettings b WHERE b.id = 1")
  Optional<BrandingSettingsView> findSettingsWithoutLogo();
}
