package io.opaa.branding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single, system-wide branding configuration row (#582, docs/design/guidelines.md#7) - a
 * singleton, not one row per organization, for the same reason {@link
 * io.opaa.audit.AuditRetentionSettings} is one: OPAA is deployed once per Behörde (ADR-0015), and
 * "das Branding" is what that one deployment looks like.
 *
 * <p><b>Every field is nullable, and that is the point.</b> {@code null} means "never configured",
 * not "empty", and {@link BrandingSettingsService} resolves it to the {@link BrandingDefaults OPAA
 * default} at read time. Seeding the row with the defaults instead would freeze today's standard
 * into every deployment's database and make "back to the OPAA standard" indistinguishable from "the
 * operator happened to type the same value".
 *
 * <p>The logo lives in this same row as a {@code bytea} rather than on disk: it is at most half a
 * megabyte ({@link BrandingLogoValidator#MAX_LOGO_SIZE_BYTES}), it must survive a restart of a
 * container that has no persistent volume, and every replica must see the same one the moment it
 * changes - all three of which a database column gives for free and a filesystem path does not.
 * {@link #logoContentType} is the type detected in the bytes at upload time, never one the uploader
 * declared.
 */
@Entity
@Table(name = "branding_settings")
public class BrandingSettings {

  /**
   * Always {@code 1} - see the class Javadoc; enforced by {@code chk_branding_settings_singleton}.
   */
  public static final int SINGLETON_ID = 1;

  @Id private Integer id;

  @Column(name = "product_name", length = 60)
  private String productName;

  @Column(name = "claim", length = 120)
  private String claim;

  @Column(name = "primary_color", length = 7)
  private String primaryColor;

  @Enumerated(EnumType.STRING)
  @Column(name = "default_color_scheme", length = 10)
  private ColorScheme defaultColorScheme;

  @Column(name = "logo_content")
  private byte[] logoContent;

  @Column(name = "logo_content_type", length = 50)
  private String logoContentType;

  @Column(name = "logo_version", length = 16)
  private String logoVersion;

  @Column(name = "logo_updated_at")
  private Instant logoUpdatedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected BrandingSettings() {}

  public Integer getId() {
    return id;
  }

  public String getProductName() {
    return productName;
  }

  public String getClaim() {
    return claim;
  }

  public String getPrimaryColor() {
    return primaryColor;
  }

  public ColorScheme getDefaultColorScheme() {
    return defaultColorScheme;
  }

  public byte[] getLogoContent() {
    return logoContent;
  }

  public String getLogoContentType() {
    return logoContentType;
  }

  public String getLogoVersion() {
    return logoVersion;
  }

  public Instant getLogoUpdatedAt() {
    return logoUpdatedAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /**
   * Replaces the four non-binary fields wholesale - a {@code null} argument means "back to the OPAA
   * default", matching {@code PUT}'s own replace-everything semantics (#582). Deliberately one
   * method rather than four setters: there is no code path that legitimately changes one of these
   * without deciding about the other three, and four setters would invite one.
   */
  void replaceSettings(
      String productName, String claim, String primaryColor, ColorScheme defaultColorScheme) {
    this.productName = productName;
    this.claim = claim;
    this.primaryColor = primaryColor;
    this.defaultColorScheme = defaultColorScheme;
    this.updatedAt = Instant.now();
  }

  /** Stores a validated logo. {@code contentType} is the detected one, never the declared one. */
  void replaceLogo(byte[] content, String contentType, String version, Instant uploadedAt) {
    this.logoContent = content;
    this.logoContentType = contentType;
    this.logoVersion = version;
    this.logoUpdatedAt = uploadedAt;
    this.updatedAt = Instant.now();
  }

  /** Removes the configured logo; the app falls back to the bundled OPAA logo. */
  void clearLogo() {
    this.logoContent = null;
    this.logoContentType = null;
    this.logoVersion = null;
    this.logoUpdatedAt = null;
    this.updatedAt = Instant.now();
  }
}
