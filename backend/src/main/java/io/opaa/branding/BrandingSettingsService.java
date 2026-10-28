package io.opaa.branding;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.ColorScheme;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditPayloadKeys;
import io.opaa.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the single, system-wide branding configuration (docs/design/guidelines.md#7) -
 * product name, claim, accent colour, default colour scheme and the operator's three images (app
 * logo, sign-in logo, sign-in background).
 *
 * <p><b>Reading never fails for want of configuration.</b> {@link #currentBranding()} resolves each
 * field individually against {@link BrandingDefaults}, so a deployment that has configured nothing
 * looks exactly like the OPAA standard, and one that configured only a logo keeps the standard
 * product name. That per-field resolution is why the stored row keeps {@code null}s rather than
 * being seeded with the defaults - see {@link BrandingSettings}'s own Javadoc.
 *
 * <p><b>Writing is validated here, before the database sees it</b> ("Validierung an der
 * Systemgrenze"), with German-language messages: the database's own {@code chk_branding_settings_*}
 * constraints of the baseline are the backstop that catches a future direct write, not the primary
 * defense that a caller is expected to hit. The rules of the text fields live in {@link
 * BrandingSettingsRules}, which the installation shares, the images' own in {@link
 * BrandingImageValidator}.
 *
 * <p>Every change records an {@link AuditEventType#BRANDING_SETTINGS_CHANGED} event ("Audit-
 * Ereignis für Branding-Änderungen"). The {@code before}/{@code after} maps carry the <em>effective
 * </em> values, not the raw stored ones: what an auditor needs to reconstruct is what the
 * operator's users saw change, and "productName: OPAA → Landesamt-Assistent" says that where "null
 * → Landesamt-Assistent" would not. An image appears in them by presence, type and version only -
 * never its bytes. The two keys of each image kind it names for the event catalog itself ({@link
 * AuditPayloadKeys}).
 */
@Service
public class BrandingSettingsService implements AuditPayloadKeys {

  /**
   * The {@code object_id} every branding audit entry carries. The branding settings are a singleton
   * with a fixed id of 1, which would be a meaningless {@code object_id} across event types, so the
   * same {@code UUID.nameUUIDFromBytes} convention {@code AuditRetentionSettingsService} uses for
   * its own system setting applies here.
   */
  private static final String CONFIGURATION_OBJECT_ID = "branding";

  private static final String OBJECT_LABEL = "Branding";

  private final BrandingSettingsRepository repository;
  private final BrandingImageValidator imageValidator;
  private final AuditEventRecorder auditEventRecorder;
  private final Clock clock;

  public BrandingSettingsService(
      BrandingSettingsRepository repository,
      BrandingImageValidator imageValidator,
      AuditEventRecorder auditEventRecorder,
      Clock clock) {
    this.repository = repository;
    this.imageValidator = imageValidator;
    this.auditEventRecorder = auditEventRecorder;
    this.clock = clock;
  }

  /**
   * The branding in effect - the operator's configured values where they exist, the OPAA default
   * everywhere else. Deliberately does not load any image bytes: this runs for every signed-in user
   * on every page render (see {@link BrandingSettingsRepository#findSettingsWithoutImages()}).
   */
  @Transactional(readOnly = true)
  public EffectiveBranding currentBranding() {
    return resolve(
        repository.findSettingsWithoutImages().orElseThrow(BrandingSettingsService::missingRow));
  }

  /**
   * The stored values without image bytes: {@code null} in a text field means the OPAA default
   * applies.
   */
  @Transactional(readOnly = true)
  public BrandingSettingsView storedSettings() {
    return repository.findSettingsWithoutImages().orElseThrow(BrandingSettingsService::missingRow);
  }

  /**
   * One configured image's bytes, or empty while none is configured for that kind. Loads that one
   * image's {@code bytea} column and no other - the serving endpoint is reachable without a session
   * and without a rate limit, so pulling all three per request would hand anyone a three-megabyte
   * read for a few hundred kilobytes of response.
   */
  @Transactional(readOnly = true)
  public Optional<BrandingImage> currentImage(BrandingImageKind kind) {
    return repository.findImage(kind);
  }

  /**
   * Replaces the four non-binary branding fields. A {@code null} argument means "back to the OPAA
   * default" rather than "leave unchanged" - {@code PUT}'s own semantics, and the only way an
   * operator can undo a customisation without a dedicated reset endpoint. Blank strings are treated
   * as {@code null} for the same reason: a cleared form field means "default", not "a name
   * consisting of spaces". Values equal to the stored ones write nothing and record nothing.
   */
  @Transactional
  public EffectiveBranding updateBranding(
      UUID organizationId,
      UUID actorUserId,
      String productName,
      String claim,
      String aiNotice,
      String primaryColor,
      ColorScheme defaultColorScheme) {
    return replace(
        validated(productName, claim, aiNotice, primaryColor, defaultColorScheme),
        (before, after) -> recordChange(organizationId, actorUserId, before, after));
  }

  /**
   * {@link #updateBranding} for the completion of the installation, which has no account yet: the
   * same rules and the same no-op rule, audited under the system process {@code systemActor}.
   */
  @Transactional
  public EffectiveBranding installBranding(
      UUID organizationId,
      String systemActor,
      String productName,
      String claim,
      String aiNotice,
      String primaryColor,
      ColorScheme defaultColorScheme) {
    return replace(
        validated(productName, claim, aiNotice, primaryColor, defaultColorScheme),
        (before, after) ->
            auditEventRecorder.recordSystemProcessAction(
                changeEvent(organizationId, before, after).actorRef(systemActor).build()));
  }

  private static BrandingSettingsRules.Values validated(
      String productName,
      String claim,
      String aiNotice,
      String primaryColor,
      ColorScheme defaultColorScheme) {
    BrandingSettingsRules.Checked checked =
        BrandingSettingsRules.check(
            "", productName, claim, aiNotice, primaryColor, defaultColorScheme);
    if (!checked.errors().isEmpty()) {
      throw new ValidationException(checked.errors().getFirst().message());
    }
    return checked.values();
  }

  private EffectiveBranding replace(
      BrandingSettingsRules.Values values,
      BiConsumer<Map<String, Object>, Map<String, Object>> recorder) {
    BrandingSettings settings =
        repository.findSingleton().orElseThrow(BrandingSettingsService::missingRow);
    if (Objects.equals(settings.getProductName(), values.productName())
        && Objects.equals(settings.getClaim(), values.claim())
        && Objects.equals(settings.getAiNotice(), values.aiNotice())
        && Objects.equals(settings.getPrimaryColor(), values.primaryColor())
        && settings.getDefaultColorScheme() == values.defaultColorScheme()) {
      return resolve(settings);
    }
    Map<String, Object> before = auditState(resolve(settings));
    settings.replaceSettings(
        values.productName(),
        values.claim(),
        values.aiNotice(),
        values.primaryColor(),
        values.defaultColorScheme());
    repository.save(settings);

    EffectiveBranding after = resolve(settings);
    recorder.accept(before, auditState(after));
    return after;
  }

  /**
   * Stores an uploaded image after {@link BrandingImageValidator} has accepted it for that kind.
   * The stored content type is the one detected in the bytes, never the one the uploader declared.
   *
   * <p>Callers that can cheaply learn the size before materialising the bytes should first call
   * {@link BrandingImageValidator#requireAcceptableSize} - see that method's own Javadoc.
   */
  @Transactional
  public EffectiveBranding replaceImage(
      UUID organizationId, UUID actorUserId, BrandingImageKind kind, byte[] content) {
    BrandingImageValidator.ValidatedImage image = imageValidator.validate(kind, content);

    BrandingSettings settings =
        repository.findSingleton().orElseThrow(BrandingSettingsService::missingRow);
    Map<String, Object> before = auditState(resolve(settings));
    settings.replaceImage(
        kind, image.content(), image.contentType(), image.version(), Instant.now(clock));
    repository.save(settings);

    EffectiveBranding after = resolve(settings);
    recordChange(organizationId, actorUserId, before, auditState(after));
    return after;
  }

  /**
   * Removes one configured image; the interface falls back to what it shows without one. Idempotent
   * - removing an image that is not there succeeds and, because nothing changed, writes no audit
   * entry.
   */
  @Transactional
  public EffectiveBranding removeImage(
      UUID organizationId, UUID actorUserId, BrandingImageKind kind) {
    BrandingSettings settings =
        repository.findSingleton().orElseThrow(BrandingSettingsService::missingRow);
    if (settings.imageContent(kind) == null) {
      return resolve(settings);
    }
    Map<String, Object> before = auditState(resolve(settings));
    settings.clearImage(kind);
    repository.save(settings);

    EffectiveBranding after = resolve(settings);
    recordChange(organizationId, actorUserId, before, auditState(after));
    return after;
  }

  private EffectiveBranding resolve(BrandingSettings settings) {
    BrandingSettingsView.StoredImage logo = settings.imageMetadata(BrandingImageKind.LOGO);
    BrandingSettingsView.StoredImage loginLogo =
        settings.imageMetadata(BrandingImageKind.LOGIN_LOGO);
    BrandingSettingsView.StoredImage background =
        settings.imageMetadata(BrandingImageKind.LOGIN_BACKGROUND);
    return resolve(
        new BrandingSettingsView(
            settings.getProductName(),
            settings.getClaim(),
            settings.getAiNotice(),
            settings.getPrimaryColor(),
            settings.getDefaultColorScheme(),
            logo.contentType(),
            logo.version(),
            logo.updatedAt(),
            loginLogo.contentType(),
            loginLogo.version(),
            loginLogo.updatedAt(),
            background.contentType(),
            background.version(),
            background.updatedAt()));
  }

  private EffectiveBranding resolve(BrandingSettingsView stored) {
    Map<BrandingImageKind, EffectiveBranding.ImageMetadata> images =
        new EnumMap<>(BrandingImageKind.class);
    for (BrandingImageKind kind : BrandingImageKind.values()) {
      BrandingSettingsView.StoredImage image = stored.image(kind);
      if (image.isPresent()) {
        images.put(
            kind,
            new EffectiveBranding.ImageMetadata(
                image.contentType(), image.version(), image.updatedAt()));
      }
    }
    return new EffectiveBranding(
        Optional.ofNullable(stored.productName()).orElse(BrandingDefaults.PRODUCT_NAME),
        Optional.ofNullable(stored.claim()).orElse(BrandingDefaults.CLAIM),
        Optional.ofNullable(stored.aiNotice()).orElse(BrandingDefaults.AI_NOTICE),
        Optional.ofNullable(stored.primaryColor()).orElse(BrandingDefaults.PRIMARY_COLOR),
        Optional.ofNullable(stored.defaultColorScheme()).orElse(BrandingDefaults.COLOR_SCHEME),
        images);
  }

  private void recordChange(
      UUID organizationId,
      UUID actorUserId,
      Map<String, Object> before,
      Map<String, Object> after) {
    auditEventRecorder.recordUserAction(
        changeEvent(organizationId, before, after).actor(actorUserId).build());
  }

  private static AuditEvent.Builder changeEvent(
      UUID organizationId, Map<String, Object> before, Map<String, Object> after) {
    return AuditEvent.builder()
        .organizationId(organizationId)
        .type(AuditEventType.BRANDING_SETTINGS_CHANGED)
        .object(
            AuditObjectType.SYSTEM_SETTING,
            UUID.nameUUIDFromBytes(CONFIGURATION_OBJECT_ID.getBytes(StandardCharsets.UTF_8)),
            OBJECT_LABEL)
        .before(before)
        .after(after)
        .outcome(AuditOutcome.SUCCESS);
  }

  /** Never an image's bytes - only that there is one, of what type, and in which version. */
  private Map<String, Object> auditState(EffectiveBranding branding) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("productName", branding.productName());
    state.put("claim", branding.claim());
    state.put("aiNotice", branding.aiNotice());
    state.put("primaryColor", branding.primaryColor());
    state.put("defaultColorScheme", branding.defaultColorScheme().name());
    for (BrandingImageKind kind : BrandingImageKind.values()) {
      Optional<EffectiveBranding.ImageMetadata> image = branding.image(kind);
      state.put(
          auditKey(kind, "ContentType"),
          image.map(EffectiveBranding.ImageMetadata::contentType).orElse("-"));
      state.put(
          auditKey(kind, "Version"),
          image.map(EffectiveBranding.ImageMetadata::version).orElse("-"));
    }
    return Map.copyOf(state);
  }

  @Override
  public AuditEventType eventType() {
    return AuditEventType.BRANDING_SETTINGS_CHANGED;
  }

  /** Content type and version of every {@link BrandingImageKind}, as {@link #auditState} writes. */
  @Override
  public Set<String> keys() {
    Set<String> keys = new LinkedHashSet<>();
    for (BrandingImageKind kind : BrandingImageKind.values()) {
      keys.add(auditKey(kind, "ContentType"));
      keys.add(auditKey(kind, "Version"));
    }
    return keys;
  }

  /**
   * {@code logoContentType}, {@code loginLogoVersion}, ... - the camelCase form of the enum
   * constant plus the aspect. The {@code LOGO} keys keep the names older audit entries carry, so a
   * query over the protocol finds them all.
   */
  private static String auditKey(BrandingImageKind kind, String aspect) {
    String[] parts = kind.name().toLowerCase(Locale.ROOT).split("_");
    StringBuilder key = new StringBuilder(parts[0]);
    for (int i = 1; i < parts.length; i++) {
      key.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
    }
    return key.append(aspect).toString();
  }

  private static IllegalStateException missingRow() {
    return new IllegalStateException(
        "branding_settings has no row with id="
            + BrandingSettings.SINGLETON_ID
            + " - the baseline should have created it");
  }
}
