package io.opaa.auth.local;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.local.LocalAuthSettings.Values;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.auth.oidc.OidcProviderService;
import io.opaa.auth.oidc.OidcProviderService.LocalAccountsSwitch;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.common.PublicBaseUrl;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The settings of the local account management (ADR-0033, Entscheidungen 3, 4 and 10). The switch
 * itself is the LOCAL provider row and is flipped through {@link OidcProviderService}, which audits
 * it and ends the sessions of regular accounts; the policy values live in {@link LocalAuthSettings}
 * and are audited as {@code LOCAL_ACCOUNTS_SETTINGS_CHANGED} with before/after of the changed keys
 * only. The two link flows cannot be switched <em>on</em> without {@code OPAA_PUBLIC_BASE_URL}; a
 * value that is already on stays as stored (the effective value visitors see is computed with the
 * base URL anyway, see {@code AuthConfigController}).
 */
@Service
public class LocalAuthSettingsService {

  public static final String PUBLIC_BASE_URL_REQUIRED = "PUBLIC_BASE_URL_REQUIRED";

  static final String OUT_OF_RANGE = "OUT_OF_RANGE";
  static final String REQUIRED = "REQUIRED";
  private static final String SETTINGS_OBJECT_ID = "local_auth_settings";
  private static final String SETTINGS_OBJECT_LABEL = "Lokale Benutzerverwaltung";

  /** The stored values, the switch and whether links are possible. */
  public record View(Values values, boolean enabled, boolean publicBaseUrlConfigured) {}

  /** A full replacement; the bounds are checked here so a violation names its field. */
  public record Update(
      boolean enabled,
      boolean selfRegistrationEnabled,
      List<String> selfRegistrationAllowedDomains,
      boolean passwordResetEnabled,
      int passwordMinLength,
      int invitationTokenTtlHours,
      int resetTokenTtlMinutes,
      int defaultExpiryDays,
      int inactiveDays) {
    public Update {
      selfRegistrationAllowedDomains =
          selfRegistrationAllowedDomains == null
              ? List.of()
              : List.copyOf(selfRegistrationAllowedDomains);
    }
  }

  /** The state after the change; {@code revokedSessions} only when the switch went off. */
  public record Updated(View view, Integer revokedSessions) {}

  private final LocalAuthSettingsRepository settings;
  private final OidcProviderRepository providers;
  private final OidcProviderService providerService;
  private final PublicBaseUrl publicBaseUrl;
  private final AuditEventRecorder audit;
  private final Clock clock;

  public LocalAuthSettingsService(
      LocalAuthSettingsRepository settings,
      OidcProviderRepository providers,
      OidcProviderService providerService,
      PublicBaseUrl publicBaseUrl,
      AuditEventRecorder audit,
      Clock clock) {
    this.settings = settings;
    this.providers = providers;
    this.providerService = providerService;
    this.publicBaseUrl = publicBaseUrl;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public View current() {
    Values values =
        settings.findSingleton().map(LocalAuthSettings::values).orElseGet(Values::defaults);
    boolean enabled = providers.findLocalRow().map(OidcProvider::isEnabled).orElse(false);
    return new View(values, enabled, publicBaseUrl.isConfigured());
  }

  @Transactional
  public Updated update(CurrentUser actor, Update update) {
    Values target = validate(update);
    View before = current();
    if (!publicBaseUrl.isConfigured()) {
      if (target.passwordResetEnabled() && !before.values().passwordResetEnabled()) {
        throw baseUrlRequired("„Passwort vergessen“");
      }
      if (target.selfRegistrationEnabled() && !before.values().selfRegistrationEnabled()) {
        throw baseUrlRequired("die Selbstregistrierung");
      }
    }
    if (!target.equals(before.values())) {
      LocalAuthSettings row =
          settings
              .findSingleton()
              .orElseThrow(() -> new IllegalStateException("local_auth_settings row is missing"));
      row.replace(target, actor.id(), clock.instant());
      settings.save(row);
      recordChange(actor, before.values(), target);
    }
    Integer revokedSessions = null;
    if (update.enabled() != before.enabled()) {
      LocalAccountsSwitch switched =
          providerService.setLocalAccountsEnabled(
              actor.organizationId(), actor.id(), update.enabled());
      if (!update.enabled()) {
        revokedSessions = switched.revokedSessions();
      }
    }
    return new Updated(current(), revokedSessions);
  }

  private static Values validate(Update update) {
    List<FieldError> errors = new ArrayList<>();
    requireBetween(
        errors,
        "passwordMinLength",
        update.passwordMinLength(),
        LocalAuthSettings.MIN_PASSWORD_MIN_LENGTH,
        LocalAuthSettings.MAX_PASSWORD_MIN_LENGTH);
    requireBetween(
        errors,
        "invitationTokenTtlHours",
        update.invitationTokenTtlHours(),
        LocalAuthSettings.MIN_INVITATION_TOKEN_TTL_HOURS,
        LocalAuthSettings.MAX_INVITATION_TOKEN_TTL_HOURS);
    requireBetween(
        errors,
        "resetTokenTtlMinutes",
        update.resetTokenTtlMinutes(),
        LocalAuthSettings.MIN_RESET_TOKEN_TTL_MINUTES,
        LocalAuthSettings.MAX_RESET_TOKEN_TTL_MINUTES);
    requireBetween(
        errors,
        "defaultExpiryDays",
        update.defaultExpiryDays(),
        LocalAuthSettings.MIN_DEFAULT_EXPIRY_DAYS,
        Integer.MAX_VALUE);
    requireBetween(
        errors,
        "inactiveDays",
        update.inactiveDays(),
        LocalAuthSettings.MIN_INACTIVE_DAYS,
        Integer.MAX_VALUE);
    List<String> domains = DomainListConverter.normalize(update.selfRegistrationAllowedDomains());
    if (update.selfRegistrationEnabled() && domains.isEmpty()) {
      errors.add(
          new FieldError(
              "selfRegistrationAllowedDomains",
              REQUIRED,
              "Die Selbstregistrierung braucht mindestens eine zulässige Adressdomäne."));
    }
    if (!errors.isEmpty()) {
      throw new FieldValidationException(
          "Die Einstellungen der lokalen Benutzerverwaltung sind unvollständig oder außerhalb der"
              + " zulässigen Grenzen.",
          errors);
    }
    return new Values(
        update.selfRegistrationEnabled(),
        domains,
        update.passwordResetEnabled(),
        update.passwordMinLength(),
        update.invitationTokenTtlHours(),
        update.resetTokenTtlMinutes(),
        update.defaultExpiryDays(),
        update.inactiveDays());
  }

  private static void requireBetween(
      List<FieldError> errors, String field, int value, int min, int max) {
    if (value < min || value > max) {
      errors.add(
          new FieldError(
              field,
              OUT_OF_RANGE,
              max == Integer.MAX_VALUE
                  ? "Der Wert muss mindestens " + min + " sein."
                  : "Der Wert muss zwischen " + min + " und " + max + " liegen."));
    }
  }

  private static ConflictException baseUrlRequired(String flow) {
    return new ConflictException(
        "Solange OPAA_PUBLIC_BASE_URL nicht gesetzt ist, kann "
            + flow
            + " nicht eingeschaltet werden: OPAA könnte keinen Link bauen, den jemand sieht.",
        PUBLIC_BASE_URL_REQUIRED);
  }

  /** Before/after of the changed keys only (ADR-0033, Entscheidung 3). */
  private void recordChange(CurrentUser actor, Values before, Values after) {
    Map<String, Object> beforeChanged = new LinkedHashMap<>();
    Map<String, Object> afterChanged = new LinkedHashMap<>();
    Map<String, Object> allBefore = asMap(before);
    Map<String, Object> allAfter = asMap(after);
    for (String key : allBefore.keySet()) {
      if (!java.util.Objects.equals(allBefore.get(key), allAfter.get(key))) {
        beforeChanged.put(key, allBefore.get(key));
        afterChanged.put(key, allAfter.get(key));
      }
    }
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.LOCAL_ACCOUNTS_SETTINGS_CHANGED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                UUID.nameUUIDFromBytes(SETTINGS_OBJECT_ID.getBytes(StandardCharsets.UTF_8)),
                SETTINGS_OBJECT_LABEL)
            .before(beforeChanged)
            .after(afterChanged)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private static Map<String, Object> asMap(Values values) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("selfRegistrationEnabled", values.selfRegistrationEnabled());
    map.put("selfRegistrationAllowedDomains", values.selfRegistrationAllowedDomains());
    map.put("passwordResetEnabled", values.passwordResetEnabled());
    map.put("passwordMinLength", values.passwordMinLength());
    map.put("invitationTokenTtlHours", values.invitationTokenTtlHours());
    map.put("resetTokenTtlMinutes", values.resetTokenTtlMinutes());
    map.put("defaultExpiryDays", values.defaultExpiryDays());
    map.put("inactiveDays", values.inactiveDays());
    return map;
  }
}
