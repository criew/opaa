package io.opaa.branding;

import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditEventType;
import io.opaa.audit.AuditObjectType;
import io.opaa.audit.AuditOutcome;
import io.opaa.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the single, system-wide branding configuration (#582,
 * docs/design/guidelines.md#7) - product name, claim, accent colour, default colour scheme and the
 * operator's logo.
 *
 * <p><b>Reading never fails for want of configuration.</b> {@link #currentBranding()} resolves each
 * field individually against {@link BrandingDefaults}, so a deployment that has configured nothing
 * looks exactly like the OPAA standard, and one that configured only a logo keeps the standard
 * product name. That per-field resolution is why the stored row keeps {@code null}s rather than
 * being seeded with the defaults - see {@link BrandingSettings}'s own Javadoc.
 *
 * <p><b>Writing is validated here, before the database sees it</b> (#582: "Validierung an der
 * Systemgrenze"), with German-language messages: the database's own {@code chk_branding_settings_*}
 * constraints (migration 041) are the backstop that catches a future direct write, not the primary
 * defense that a caller is expected to hit. The logo's own rules live in {@link
 * BrandingLogoValidator}.
 *
 * <p>Every change records an {@link AuditEventType#BRANDING_SETTINGS_CHANGED} event (#582: "Audit-
 * Ereignis für Branding-Änderungen"). The {@code before}/{@code after} maps carry the <em>effective
 * </em> values, not the raw stored ones: what an auditor needs to reconstruct is what the
 * operator's users saw change, and "productName: OPAA → Landesamt-Assistent" says that where "null
 * → Landesamt-Assistent" would not. The logo appears in them by presence, type and version only -
 * never its bytes.
 */
@Service
public class BrandingSettingsService {

  /**
   * Six-digit hex triplet with a leading '#'. Deliberately no three-digit short form and no named
   * colours: the frontend derives hover/press/focus states from this value by darkening it (#581),
   * and one canonical input form keeps that derivation - and the contrast check in #583 - from
   * having to parse variants.
   */
  private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

  /**
   * The {@code object_id} every branding audit entry carries. The branding settings are a singleton
   * with a fixed id of 1, which would be a meaningless {@code object_id} across event types, so the
   * same {@code UUID.nameUUIDFromBytes} convention {@code AuditRetentionSettingsService} uses for
   * its own system setting applies here.
   */
  private static final String CONFIGURATION_OBJECT_ID = "branding";

  private static final String OBJECT_LABEL = "Branding";

  private final BrandingSettingsRepository repository;
  private final BrandingLogoValidator logoValidator;
  private final AuditEventRecorder auditEventRecorder;

  public BrandingSettingsService(
      BrandingSettingsRepository repository,
      BrandingLogoValidator logoValidator,
      AuditEventRecorder auditEventRecorder) {
    this.repository = repository;
    this.logoValidator = logoValidator;
    this.auditEventRecorder = auditEventRecorder;
  }

  /**
   * The branding in effect - the operator's configured values where they exist, the OPAA default
   * everywhere else. Deliberately does not load the logo bytes: this runs for every signed-in user
   * on every page render (see {@link BrandingSettingsRepository#findSettingsWithoutLogo()}).
   */
  @Transactional(readOnly = true)
  public EffectiveBranding currentBranding() {
    return resolve(
        repository.findSettingsWithoutLogo().orElseThrow(BrandingSettingsService::missingRow));
  }

  /**
   * The configured logo's bytes, or empty while none is configured. The only read path that loads
   * the {@code bytea} column.
   */
  @Transactional(readOnly = true)
  public Optional<BrandingLogo> currentLogo() {
    BrandingSettings settings =
        repository.findSingleton().orElseThrow(BrandingSettingsService::missingRow);
    if (settings.getLogoContent() == null) {
      return Optional.empty();
    }
    return Optional.of(
        new BrandingLogo(
            settings.getLogoContent(), settings.getLogoContentType(), settings.getLogoVersion()));
  }

  /**
   * Replaces the four non-binary branding fields. A {@code null} argument means "back to the OPAA
   * default" rather than "leave unchanged" - {@code PUT}'s own semantics (#582), and the only way
   * an operator can undo a customisation without a dedicated reset endpoint. Blank strings are
   * treated as {@code null} for the same reason: a cleared form field means "default", not "a name
   * consisting of spaces".
   */
  @Transactional
  public EffectiveBranding updateBranding(
      UUID organizationId,
      UUID actorUserId,
      String productName,
      String claim,
      String primaryColor,
      ColorScheme defaultColorScheme) {
    String validatedProductName =
        validatedText(productName, BrandingDefaults.MAX_PRODUCT_NAME_LENGTH, "Der Produktname");
    String validatedClaim = validatedText(claim, BrandingDefaults.MAX_CLAIM_LENGTH, "Der Claim");
    String validatedColor = validatedColor(primaryColor);

    BrandingSettings settings =
        repository.findSingleton().orElseThrow(BrandingSettingsService::missingRow);
    Map<String, Object> before = auditState(resolve(settings));
    settings.replaceSettings(
        validatedProductName, validatedClaim, validatedColor, defaultColorScheme);
    repository.save(settings);

    EffectiveBranding after = resolve(settings);
    recordChange(organizationId, actorUserId, before, auditState(after));
    return after;
  }

  /**
   * Stores an uploaded logo after {@link BrandingLogoValidator} has accepted it. The stored content
   * type is the one detected in the bytes, never the one the uploader declared.
   *
   * <p>Callers that can cheaply learn the size before materialising the bytes should first call
   * {@link BrandingLogoValidator#requireAcceptableSize} - see that method's own Javadoc.
   */
  @Transactional
  public EffectiveBranding replaceLogo(UUID organizationId, UUID actorUserId, byte[] content) {
    BrandingLogoValidator.ValidatedLogo logo = logoValidator.validate(content);

    BrandingSettings settings =
        repository.findSingleton().orElseThrow(BrandingSettingsService::missingRow);
    Map<String, Object> before = auditState(resolve(settings));
    settings.replaceLogo(logo.content(), logo.contentType(), logo.version(), Instant.now());
    repository.save(settings);

    EffectiveBranding after = resolve(settings);
    recordChange(organizationId, actorUserId, before, auditState(after));
    return after;
  }

  /**
   * Removes the configured logo; the app falls back to the bundled OPAA logo. Idempotent - removing
   * a logo that is not there succeeds and, because nothing changed, writes no audit entry.
   */
  @Transactional
  public EffectiveBranding removeLogo(UUID organizationId, UUID actorUserId) {
    BrandingSettings settings =
        repository.findSingleton().orElseThrow(BrandingSettingsService::missingRow);
    if (settings.getLogoContent() == null) {
      return resolve(settings);
    }
    Map<String, Object> before = auditState(resolve(settings));
    settings.clearLogo();
    repository.save(settings);

    EffectiveBranding after = resolve(settings);
    recordChange(organizationId, actorUserId, before, auditState(after));
    return after;
  }

  private EffectiveBranding resolve(BrandingSettings settings) {
    return resolve(
        new BrandingSettingsView(
            settings.getProductName(),
            settings.getClaim(),
            settings.getPrimaryColor(),
            settings.getDefaultColorScheme(),
            settings.getLogoContentType(),
            settings.getLogoVersion(),
            settings.getLogoUpdatedAt()));
  }

  private EffectiveBranding resolve(BrandingSettingsView stored) {
    Optional<EffectiveBranding.LogoMetadata> logo =
        stored.logoVersion() == null
            ? Optional.empty()
            : Optional.of(
                new EffectiveBranding.LogoMetadata(
                    stored.logoContentType(), stored.logoVersion(), stored.logoUpdatedAt()));
    return new EffectiveBranding(
        Optional.ofNullable(stored.productName()).orElse(BrandingDefaults.PRODUCT_NAME),
        Optional.ofNullable(stored.claim()).orElse(BrandingDefaults.CLAIM),
        Optional.ofNullable(stored.primaryColor()).orElse(BrandingDefaults.PRIMARY_COLOR),
        Optional.ofNullable(stored.defaultColorScheme()).orElse(BrandingDefaults.COLOR_SCHEME),
        logo);
  }

  /**
   * Trims, turns blank into {@code null} ("back to the default") and rejects anything too long or
   * carrying a control character - a line break in a product name is a layout defect on every page
   * that renders it, not a customisation.
   */
  private String validatedText(String value, int maxLength, String fieldLabel) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > maxLength) {
      throw new ValidationException(
          fieldLabel + " darf höchstens " + maxLength + " Zeichen lang sein");
    }
    if (trimmed.chars().anyMatch(Character::isISOControl)) {
      throw new ValidationException(fieldLabel + " darf keine Steuerzeichen enthalten");
    }
    return trimmed;
  }

  private String validatedColor(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (!HEX_COLOR.matcher(trimmed).matches()) {
      throw new ValidationException(
          "Die Primärfarbe muss ein sechsstelliger Hex-Wert mit führendem '#' sein, zum Beispiel"
              + " #1292EE");
    }
    return trimmed;
  }

  private void recordChange(
      UUID organizationId,
      UUID actorUserId,
      Map<String, Object> before,
      Map<String, Object> after) {
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.BRANDING_SETTINGS_CHANGED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                UUID.nameUUIDFromBytes(CONFIGURATION_OBJECT_ID.getBytes(StandardCharsets.UTF_8)),
                OBJECT_LABEL)
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /** Never the logo's bytes - only that there is one, of what type, and in which version. */
  private Map<String, Object> auditState(EffectiveBranding branding) {
    return Map.of(
        "productName", branding.productName(),
        "claim", branding.claim(),
        "primaryColor", branding.primaryColor(),
        "defaultColorScheme", branding.defaultColorScheme().name(),
        "logoContentType",
            branding.logo().map(EffectiveBranding.LogoMetadata::contentType).orElse("-"),
        "logoVersion", branding.logo().map(EffectiveBranding.LogoMetadata::version).orElse("-"));
  }

  private static IllegalStateException missingRow() {
    return new IllegalStateException(
        "branding_settings has no row with id="
            + BrandingSettings.SINGLETON_ID
            + " - migration 041 should have created it");
  }
}
