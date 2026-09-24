package io.opaa.branding;

import io.opaa.api.types.ColorScheme;
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
 * <p>The images live in this same row as {@code bytea} rather than on disk: each is at most a few
 * hundred kilobytes ({@link BrandingImageKind#maxSizeBytes()}), they must survive a restart of a
 * container that has no persistent volume, and every replica must see the same ones the moment they
 * change - all three of which a database column gives for free and a filesystem path does not. The
 * stored content type is always the type detected in the bytes at upload time, never one the
 * uploader declared.
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

  @Column(name = "login_logo_content")
  private byte[] loginLogoContent;

  @Column(name = "login_logo_content_type", length = 50)
  private String loginLogoContentType;

  @Column(name = "login_logo_version", length = 16)
  private String loginLogoVersion;

  @Column(name = "login_logo_updated_at")
  private Instant loginLogoUpdatedAt;

  @Column(name = "login_background_content")
  private byte[] loginBackgroundContent;

  @Column(name = "login_background_content_type", length = 50)
  private String loginBackgroundContentType;

  @Column(name = "login_background_version", length = 16)
  private String loginBackgroundVersion;

  @Column(name = "login_background_updated_at")
  private Instant loginBackgroundUpdatedAt;

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

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** The stored bytes of one image, or {@code null} while none is configured for that kind. */
  byte[] imageContent(BrandingImageKind kind) {
    return switch (kind) {
      case LOGO -> logoContent;
      case LOGIN_LOGO -> loginLogoContent;
      case LOGIN_BACKGROUND -> loginBackgroundContent;
    };
  }

  /**
   * Everything known about one image except its bytes; all fields {@code null} while none is set.
   */
  BrandingSettingsView.StoredImage imageMetadata(BrandingImageKind kind) {
    return switch (kind) {
      case LOGO ->
          new BrandingSettingsView.StoredImage(logoContentType, logoVersion, logoUpdatedAt);
      case LOGIN_LOGO ->
          new BrandingSettingsView.StoredImage(
              loginLogoContentType, loginLogoVersion, loginLogoUpdatedAt);
      case LOGIN_BACKGROUND ->
          new BrandingSettingsView.StoredImage(
              loginBackgroundContentType, loginBackgroundVersion, loginBackgroundUpdatedAt);
    };
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

  /**
   * Stores a validated image. {@code contentType} is the detected one, never the declared one.
   * Passing {@code null} for all four clears the slot, which is what {@link #clearImage} does - the
   * database's {@code chk_branding_settings_*_complete} constraints reject every partial state.
   */
  void replaceImage(
      BrandingImageKind kind,
      byte[] content,
      String contentType,
      String version,
      Instant uploadedAt) {
    switch (kind) {
      case LOGO -> {
        this.logoContent = content;
        this.logoContentType = contentType;
        this.logoVersion = version;
        this.logoUpdatedAt = uploadedAt;
      }
      case LOGIN_LOGO -> {
        this.loginLogoContent = content;
        this.loginLogoContentType = contentType;
        this.loginLogoVersion = version;
        this.loginLogoUpdatedAt = uploadedAt;
      }
      case LOGIN_BACKGROUND -> {
        this.loginBackgroundContent = content;
        this.loginBackgroundContentType = contentType;
        this.loginBackgroundVersion = version;
        this.loginBackgroundUpdatedAt = uploadedAt;
      }
    }
    this.updatedAt = Instant.now();
  }

  /** Removes one configured image; the interface falls back to what it shows without one. */
  void clearImage(BrandingImageKind kind) {
    replaceImage(kind, null, null, null, null);
  }
}
