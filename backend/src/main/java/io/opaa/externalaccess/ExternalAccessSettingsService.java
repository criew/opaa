package io.opaa.externalaccess;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.externalaccess.ExternalAccessSettings.Values;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The channel settings of the external access (#1717, ADR-0035; docs/features/external-access.md,
 * "Der Schalter der Installation"). {@link #isEnabled()} is the read the later access paths make on
 * <em>every</em> call, which is why it stays a single row lookup without a cache: a cache here
 * would only buy an invalidation question and could let an emergency stop lag behind.
 *
 * <p>Every change except {@code serverInstructions} is audited as {@link
 * AuditEventType#EXTERNAL_ACCESS_SETTINGS_CHANGED} with before/after of the changed keys. The
 * instructions text is deliberately outside the closed event list of
 * docs/features/security-and-compliance.md - it changes no reach, only the wording of a request to
 * a foreign model.
 */
@Service
public class ExternalAccessSettingsService {

  static final String OUT_OF_RANGE = "OUT_OF_RANGE";
  static final String INVALID = "INVALID";
  static final String TOO_MANY = "TOO_MANY";
  static final String TOO_LONG = "TOO_LONG";

  /** The audited keys - the instructions text is not among them, by decision. */
  static final List<String> AUDITED_KEYS =
      List.of(
          "enabled",
          "tokenMaxLifetimeDays",
          "tokenRateLimitPerHour",
          "allowedCidrs",
          "massRetrievalAlertThreshold");

  private static final String SETTINGS_OBJECT_ID = "external_access_settings";
  private static final String SETTINGS_OBJECT_LABEL = "Fremdzugänge";

  /** The stored values plus when and by whom they were changed last. */
  public record View(Values values, Instant updatedAt, String updatedBy) {}

  /** A full replacement; the bounds are checked here so a violation names its field. */
  public record Update(
      boolean enabled,
      int tokenMaxLifetimeDays,
      int tokenRateLimitPerHour,
      List<String> allowedCidrs,
      int massRetrievalAlertThreshold,
      String serverInstructions) {
    public Update {
      allowedCidrs = allowedCidrs == null ? List.of() : List.copyOf(allowedCidrs);
    }
  }

  private final ExternalAccessSettingsRepository settings;
  private final UserRepository users;
  private final AuditEventRecorder audit;
  private final Clock clock;

  public ExternalAccessSettingsService(
      ExternalAccessSettingsRepository settings,
      UserRepository users,
      AuditEventRecorder audit,
      Clock clock) {
    this.settings = settings;
    this.users = users;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Whether the channel is open right now. The later access paths ask this per call, not per
   * session: an emergency stop that an open session outlives would make "Kanal ist zu" a lie.
   */
  @Transactional(readOnly = true)
  public boolean isEnabled() {
    return settings.findSingleton().map(ExternalAccessSettings::isEnabled).orElse(false);
  }

  /** The networks the whole channel may be reached from; an empty list allows nobody. */
  @Transactional(readOnly = true)
  public List<String> allowedCidrs() {
    return settings
        .findSingleton()
        .map(ExternalAccessSettings::getAllowedCidrs)
        .orElseGet(List::of);
  }

  @Transactional(readOnly = true)
  public View current() {
    return settings
        .findSingleton()
        .map(row -> new View(row.values(), row.getUpdatedAt(), displayNameOf(row.getUpdatedBy())))
        .orElseGet(() -> new View(Values.defaults(), null, null));
  }

  @Transactional
  public View update(CurrentUser actor, Update update) {
    Values target = validate(update);
    ExternalAccessSettings row =
        settings
            .findSingleton()
            .orElseThrow(
                () -> new IllegalStateException("external_access_settings row is missing"));
    Values before = row.values();
    if (target.equals(before)) {
      return new View(before, row.getUpdatedAt(), displayNameOf(row.getUpdatedBy()));
    }
    row.replace(target, actor.id(), clock.instant());
    settings.save(row);
    recordChange(actor, before, target);
    return new View(target, row.getUpdatedAt(), displayNameOf(row.getUpdatedBy()));
  }

  private String displayNameOf(UUID userId) {
    if (userId == null) {
      return null;
    }
    return users.findById(userId).map(User::getDisplayName).orElse(null);
  }

  private static Values validate(Update update) {
    List<FieldError> errors = new ArrayList<>();
    requireBetween(
        errors,
        "tokenMaxLifetimeDays",
        update.tokenMaxLifetimeDays(),
        ExternalAccessDefaults.MIN_TOKEN_MAX_LIFETIME_DAYS,
        ExternalAccessDefaults.MAX_TOKEN_MAX_LIFETIME_DAYS);
    requireBetween(
        errors,
        "tokenRateLimitPerHour",
        update.tokenRateLimitPerHour(),
        ExternalAccessDefaults.MIN_TOKEN_RATE_LIMIT_PER_HOUR,
        ExternalAccessDefaults.MAX_TOKEN_RATE_LIMIT_PER_HOUR);
    requireBetween(
        errors,
        "massRetrievalAlertThreshold",
        update.massRetrievalAlertThreshold(),
        ExternalAccessDefaults.MIN_MASS_RETRIEVAL_ALERT_THRESHOLD,
        ExternalAccessDefaults.MAX_MASS_RETRIEVAL_ALERT_THRESHOLD);
    List<String> cidrs = CidrList.normalize(update.allowedCidrs());
    if (cidrs.size() > ExternalAccessDefaults.MAX_ALLOWED_CIDRS) {
      errors.add(
          new FieldError(
              "allowedCidrs",
              TOO_MANY,
              "Es sind höchstens "
                  + ExternalAccessDefaults.MAX_ALLOWED_CIDRS
                  + " Netzbereiche zulässig."));
    }
    List<String> invalid = cidrs.stream().filter(cidr -> !CidrList.isValid(cidr)).toList();
    if (!invalid.isEmpty()) {
      errors.add(
          new FieldError(
              "allowedCidrs",
              INVALID,
              "Keine gültige Netzangabe: " + String.join(", ", invalid) + "."));
    }
    String instructions = update.serverInstructions() == null ? "" : update.serverInstructions();
    if (instructions.strip().length() > ExternalAccessDefaults.MAX_SERVER_INSTRUCTIONS_LENGTH) {
      errors.add(
          new FieldError(
              "serverInstructions",
              TOO_LONG,
              "Der Einleitungstext darf höchstens "
                  + ExternalAccessDefaults.MAX_SERVER_INSTRUCTIONS_LENGTH
                  + " Zeichen lang sein."));
    }
    if (!errors.isEmpty()) {
      throw new FieldValidationException(
          "Die Einstellungen der Fremdzugänge sind unvollständig oder außerhalb der zulässigen"
              + " Grenzen.",
          errors);
    }
    return new Values(
        update.enabled(),
        update.tokenMaxLifetimeDays(),
        update.tokenRateLimitPerHour(),
        cidrs,
        update.massRetrievalAlertThreshold(),
        instructions);
  }

  private static void requireBetween(
      List<FieldError> errors, String field, int value, int min, int max) {
    if (value < min || value > max) {
      errors.add(
          new FieldError(
              field, OUT_OF_RANGE, "Der Wert muss zwischen " + min + " und " + max + " liegen."));
    }
  }

  /**
   * Before/after of the changed keys only, and only of the keys that decide reach: a change that
   * touches nothing but the instructions text writes no entry at all.
   */
  private void recordChange(CurrentUser actor, Values before, Values after) {
    Map<String, Object> allBefore = asAuditedMap(before);
    Map<String, Object> allAfter = asAuditedMap(after);
    Map<String, Object> beforeChanged = new LinkedHashMap<>();
    Map<String, Object> afterChanged = new LinkedHashMap<>();
    for (String key : AUDITED_KEYS) {
      if (!Objects.equals(allBefore.get(key), allAfter.get(key))) {
        beforeChanged.put(key, allBefore.get(key));
        afterChanged.put(key, allAfter.get(key));
      }
    }
    if (beforeChanged.isEmpty()) {
      return;
    }
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.EXTERNAL_ACCESS_SETTINGS_CHANGED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                UUID.nameUUIDFromBytes(SETTINGS_OBJECT_ID.getBytes(StandardCharsets.UTF_8)),
                SETTINGS_OBJECT_LABEL)
            .before(beforeChanged)
            .after(afterChanged)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private static Map<String, Object> asAuditedMap(Values values) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("enabled", values.enabled());
    map.put("tokenMaxLifetimeDays", values.tokenMaxLifetimeDays());
    map.put("tokenRateLimitPerHour", values.tokenRateLimitPerHour());
    map.put("allowedCidrs", values.allowedCidrs());
    map.put("massRetrievalAlertThreshold", values.massRetrievalAlertThreshold());
    return map;
  }
}
