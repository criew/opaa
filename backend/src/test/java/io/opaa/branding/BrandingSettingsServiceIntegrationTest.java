package io.opaa.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.audit.AuditEventType;
import io.opaa.audit.AuditObjectType;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #582: {@link BrandingSettingsService} against a real Postgres with the real, versioned Liquibase
 * schema applied (migrations 041/042). Covers what a test against a mocked repository could not -
 * that an unconfigured deployment really does resolve to the OPAA standard field by field, that a
 * change writes exactly one {@link AuditEventType#BRANDING_SETTINGS_CHANGED} entry carrying the
 * effective before/after state, that a logo survives a round trip through the {@code bytea} column,
 * and that the database's own constraints reject what the service rejects (they are the backstop,
 * not the primary defense - see the service's Javadoc).
 *
 * <p>Carries the canonical {@link io.opaa.test.OpaaIntegrationTest} signature (AGENTS.md, "Spring-
 * Testkontexte"), so it shares one cached context and one container with every other class on that
 * same meta-annotation, including {@code AuditRetentionSettingsServiceIntegrationTest}. HTTP-level
 * concerns (the 403 for a non-administrator, the response headers) live in {@code
 * BrandingControllerIntegrationTest}, which needs MockMvc and therefore cannot share this context.
 */
@OpaaIntegrationTest
class BrandingSettingsServiceIntegrationTest {

  @Autowired private BrandingSettingsService brandingSettingsService;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID userId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Branding Test Org"))
            .getId();
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "branding@example.com", "Test");
    user.setOrganizationId(organizationId);
    userId = userRepository.save(user).getId();
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    resetBrandingRow();
    userRepository.deleteById(userId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void anUnconfiguredDeploymentResolvesToTheOpaaStandard() {
    EffectiveBranding branding = brandingSettingsService.currentBranding();

    assertThat(branding.productName()).isEqualTo(BrandingDefaults.PRODUCT_NAME);
    assertThat(branding.claim()).isEqualTo(BrandingDefaults.CLAIM);
    assertThat(branding.primaryColor()).isEqualTo(BrandingDefaults.PRIMARY_COLOR);
    assertThat(branding.defaultColorScheme()).isEqualTo(BrandingDefaults.COLOR_SCHEME);
    assertThat(branding.logo()).isEmpty();
  }

  @Test
  void eachFieldFallsBackToTheStandardIndependentlyOfTheOthers() {
    brandingSettingsService.updateBranding(
        organizationId, userId, "Landesamt-Assistent", null, null, null);

    EffectiveBranding branding = brandingSettingsService.currentBranding();
    assertThat(branding.productName()).isEqualTo("Landesamt-Assistent");
    assertThat(branding.claim()).isEqualTo(BrandingDefaults.CLAIM);
    assertThat(branding.primaryColor()).isEqualTo(BrandingDefaults.PRIMARY_COLOR);
    assertThat(branding.defaultColorScheme()).isEqualTo(BrandingDefaults.COLOR_SCHEME);
  }

  @Test
  void aNullFieldMeansBackToTheStandardRatherThanLeaveAsIs() {
    brandingSettingsService.updateBranding(
        organizationId,
        userId,
        "Landesamt-Assistent",
        "Kurz und klar",
        "#7A1FA2",
        ColorScheme.DARK);

    brandingSettingsService.updateBranding(organizationId, userId, null, null, null, null);

    EffectiveBranding branding = brandingSettingsService.currentBranding();
    assertThat(branding.productName()).isEqualTo(BrandingDefaults.PRODUCT_NAME);
    assertThat(branding.claim()).isEqualTo(BrandingDefaults.CLAIM);
    assertThat(branding.primaryColor()).isEqualTo(BrandingDefaults.PRIMARY_COLOR);
    assertThat(branding.defaultColorScheme()).isEqualTo(BrandingDefaults.COLOR_SCHEME);
  }

  @Test
  void aBlankFieldIsTreatedAsBackToTheStandardRatherThanAsAValue() {
    brandingSettingsService.updateBranding(organizationId, userId, "   ", "  ", "  ", null);

    assertThat(brandingSettingsService.currentBranding().productName())
        .isEqualTo(BrandingDefaults.PRODUCT_NAME);
    assertThat(jdbcTemplate.queryForObject(storedColumn("product_name"), String.class)).isNull();
  }

  @Test
  void aChangeIsAuditedExactlyOnceWithTheEffectiveBeforeAndAfterState() {
    brandingSettingsService.updateBranding(
        organizationId, userId, "Landesamt-Assistent", null, "#7A1FA2", ColorScheme.LIGHT);

    List<Map<String, Object>> entries = brandingAuditEntries();
    assertThat(entries).hasSize(1);
    Map<String, Object> entry = entries.getFirst();
    assertThat(entry.get("object_type")).isEqualTo(AuditObjectType.SYSTEM_SETTING.name());
    assertThat(entry.get("object_label")).isEqualTo("Branding");
    // The effective state, not the raw stored one: "OPAA -> Landesamt-Assistent" is what an
    // auditor can reconstruct a change from, "null -> Landesamt-Assistent" is not.
    assertThat((String) entry.get("before"))
        .contains(BrandingDefaults.PRODUCT_NAME)
        .contains(BrandingDefaults.PRIMARY_COLOR);
    assertThat((String) entry.get("after")).contains("Landesamt-Assistent").contains("#7A1FA2");
  }

  @Test
  void aRejectedChangeWritesNothingAtAll() {
    assertThatThrownBy(
            () ->
                brandingSettingsService.updateBranding(
                    organizationId, userId, "Landesamt-Assistent", null, "blau", null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Primärfarbe");

    assertThat(brandingSettingsService.currentBranding().productName())
        .isEqualTo(BrandingDefaults.PRODUCT_NAME);
    assertThat(brandingAuditEntries()).isEmpty();
  }

  @Test
  void aProductNameBeyondTheLengthLimitIsRejected() {
    String tooLong = "x".repeat(BrandingDefaults.MAX_PRODUCT_NAME_LENGTH + 1);

    assertThatThrownBy(
            () ->
                brandingSettingsService.updateBranding(
                    organizationId, userId, tooLong, null, null, null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Produktname");
  }

  @Test
  void aProductNameWithAControlCharacterIsRejected() {
    assertThatThrownBy(
            () ->
                brandingSettingsService.updateBranding(
                    organizationId, userId, "Zeile\nUmbruch", null, null, null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Steuerzeichen");
  }

  @Test
  void aLogoSurvivesTheRoundTripAndIsAuditedByPresenceNotByContent() throws IOException {
    byte[] content = png(120, 40);

    EffectiveBranding afterUpload =
        brandingSettingsService.replaceLogo(organizationId, userId, content);

    assertThat(afterUpload.logo()).isPresent();
    assertThat(afterUpload.logo().orElseThrow().contentType())
        .isEqualTo(BrandingLogoValidator.PNG_MIME_TYPE);
    BrandingLogo stored = brandingSettingsService.currentLogo().orElseThrow();
    assertThat(stored.content()).isEqualTo(content);
    assertThat(stored.contentType()).isEqualTo(BrandingLogoValidator.PNG_MIME_TYPE);

    String after = (String) brandingAuditEntries().getFirst().get("after");
    assertThat(after).contains(BrandingLogoValidator.PNG_MIME_TYPE).contains(stored.version());
  }

  @Test
  void removingALogoClearsItAndRemovingANonExistentOneAuditsNothing() throws IOException {
    brandingSettingsService.removeLogo(organizationId, userId);
    assertThat(brandingAuditEntries()).isEmpty();

    brandingSettingsService.replaceLogo(organizationId, userId, png(120, 40));
    brandingSettingsService.removeLogo(organizationId, userId);

    assertThat(brandingSettingsService.currentLogo()).isEmpty();
    assertThat(brandingSettingsService.currentBranding().logo()).isEmpty();
    assertThat(brandingAuditEntries()).hasSize(2);
  }

  @Test
  void theDatabaseRejectsAnInvalidColourEvenWhenTheServiceIsBypassed() {
    // The service is the primary defense; this proves the backstop from migration 041 is real, so
    // a future write path that forgets to validate cannot quietly store nonsense.
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE branding_settings SET primary_color = 'blau' WHERE id = 1"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_branding_settings_primary_color");
  }

  @Test
  void theDatabaseRejectsLogoBytesWithoutAContentType() {
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE branding_settings SET logo_content = decode('89504e47', 'hex')"
                        + " WHERE id = 1"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_branding_settings_logo_complete");
  }

  /**
   * Reads the audit trail through SQL rather than through {@code AuditLogRepository}: that
   * repository is package-private on purpose (see {@code io.opaa.audit}'s package-info), and
   * widening it to public so a test in another package can autowire it would trade a real
   * encapsulation boundary for test convenience.
   */
  private List<Map<String, Object>> brandingAuditEntries() {
    return jdbcTemplate.queryForList(
        "SELECT object_type, object_label, before, after FROM audit_log"
            + " WHERE organization_id = ? AND event_type = ? ORDER BY recorded_at",
        organizationId,
        AuditEventType.BRANDING_SETTINGS_CHANGED.name());
  }

  private String storedColumn(String column) {
    return "SELECT " + column + " FROM branding_settings WHERE id = 1";
  }

  private void resetBrandingRow() {
    jdbcTemplate.update(
        "UPDATE branding_settings SET product_name = NULL, claim = NULL, primary_color = NULL,"
            + " default_color_scheme = NULL, logo_content = NULL, logo_content_type = NULL,"
            + " logo_version = NULL, logo_updated_at = NULL, updated_at = now() WHERE id = 1");
  }

  private static byte[] png(int width, int height) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out);
    return out.toByteArray();
  }
}
