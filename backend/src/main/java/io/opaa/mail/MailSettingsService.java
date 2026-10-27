package io.opaa.mail;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.MailEncryption;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditSecretChange;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.common.ValidationException;
import io.opaa.instance.InvalidationBus;
import io.opaa.instance.InvalidationSubscriber;
import io.opaa.security.SettingsEncryptor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

/**
 * Reads and changes the systemwide SMTP configuration (ADR-0033 Entscheidung 10) and owns the
 * process-local snapshot every send path reads.
 *
 * <p>{@link #snapshot()} answers from memory, so sending opens no transaction; it is rebuilt on
 * first use and on every {@link MailSettingsChangedEvent} <em>after</em> its transaction committed,
 * which is what makes a change effective without a restart. Bound to the {@link InvalidationBus}
 * (ADR-0053, Entscheidung 5): a change of the settings on any instance drops it on every instance,
 * and while the bus does not listen every read builds it from the row. The send status is not part
 * of it: it is read from the row on every call, so every instance reports the outcome of the last
 * attempt of any instance (ADR-0053, Entscheidung 12).
 *
 * <p>The password is stored encrypted by {@link SettingsEncryptor}, answered as {@link
 * #PASSWORD_MASK} and never part of the audit payload, which records only whether one is set.
 */
@Service
public class MailSettingsService implements InvalidationSubscriber {

  /** The topic the trigger on {@code mail_settings} announces. */
  public static final String INVALIDATION_TOPIC = "mail-settings";

  /**
   * What the API answers instead of the stored password, and what a caller sends back to say
   * "unchanged". Deliberately a value that cannot be a password anybody typed: three asterisks are
   * below every length minimum a mail server enforces.
   */
  public static final String PASSWORD_MASK = "***";

  /**
   * The {@code object_id} every audit entry about the SMTP configuration carries - a singleton has
   * no id of its own, so the {@code UUID.nameUUIDFromBytes} convention {@code
   * BrandingSettingsService} uses applies here. Shared with {@link MailTestService}, whose test
   * send is an act on these settings.
   */
  static final String SETTINGS_OBJECT_ID = "mail-settings";

  static final String SETTINGS_OBJECT_LABEL = "E-Mail-Versand";

  private final MailSettingsRepository repository;
  private final SettingsEncryptor settingsEncryptor;
  private final AuditEventRecorder auditEventRecorder;
  private final ApplicationEventPublisher eventPublisher;
  private final InvalidationBus bus;

  private final AtomicReference<MailSettingsSnapshot> snapshot = new AtomicReference<>();
  private final AtomicLong snapshotGeneration = new AtomicLong();

  public MailSettingsService(
      MailSettingsRepository repository,
      SettingsEncryptor settingsEncryptor,
      AuditEventRecorder auditEventRecorder,
      ApplicationEventPublisher eventPublisher,
      InvalidationBus bus) {
    this.repository = repository;
    this.settingsEncryptor = settingsEncryptor;
    this.auditEventRecorder = auditEventRecorder;
    this.eventPublisher = eventPublisher;
    this.bus = bus;
  }

  /** The stored row, for the administration API. Never carries a decrypted password. */
  @Transactional(readOnly = true)
  public MailSettings currentSettings() {
    return requireRow();
  }

  /**
   * The configuration every send reads, built once and kept until the next committed change. The
   * decryption of the stored password happens here, so a missing or wrong {@code
   * OPAA_SETTINGS_ENCRYPTION_KEY} surfaces as a failed send with a clear cause rather than as a
   * silently unauthenticated connection.
   */
  public MailSettingsSnapshot snapshot() {
    if (!bus.isListening()) {
      return toSnapshot(requireRow());
    }
    MailSettingsSnapshot current = snapshot.get();
    if (current == null) {
      current = rebuildSnapshot();
    }
    return current;
  }

  /**
   * When mail last worked and when it last did not, on whichever instance it was sent; read by
   * {@link MailHealthIndicator}. One read of the single settings row per call.
   */
  @Transactional(readOnly = true)
  public MailSendStatus status() {
    MailSettings settings = requireRow();
    return new MailSendStatus(
        settings.getLastSuccessAt(), settings.getLastFailureAt(), settings.getLastFailureReason());
  }

  /**
   * Replaces every administrable field. Enabling requires a host, a port and a sender address:
   * "eingeschaltet, aber unvollständig" would report itself as configured and then skip every
   * message, which is exactly the failure this endpoint exists to make impossible.
   */
  @Transactional
  public MailSettings updateSettings(
      UUID organizationId, UUID actorUserId, MailSettingsUpdate update) {
    return replace(
        update,
        actorUserId,
        (before, after) -> recordChange(organizationId, actorUserId, before, after));
  }

  /**
   * {@link #updateSettings} for the completion of the installation: the same rules, recorded under
   * the system process {@code systemActor} instead of a person, in the caller's transaction.
   */
  @Transactional
  public MailSettings installSettings(
      UUID organizationId, String systemActor, MailSettingsUpdate update) {
    return replace(
        update,
        null,
        (before, after) ->
            auditEventRecorder.recordSystemProcessAction(
                changeEvent(organizationId, before, after).actorRef(systemActor).build()));
  }

  private MailSettings replace(
      MailSettingsUpdate update,
      UUID actorUserId,
      BiConsumer<Map<String, Object>, Map<String, Object>> recorder) {
    requireWithinBounds(update);
    String host = MailSettingsRules.trimmedOrNull(update.host());
    Integer port = validatedPort(update.port());
    String username = MailSettingsRules.trimmedOrNull(update.username());
    String fromAddress = validatedAddress(MailSettingsRules.trimmedOrNull(update.fromAddress()));
    String fromName = MailSettingsRules.trimmedOrNull(update.fromName());
    MailEncryption encryption =
        update.encryption() == null ? MailSettingsRules.DEFAULT_ENCRYPTION : update.encryption();

    MailSettings settings = requireRow();
    String passwordCiphertext =
        resolvePassword(update.password(), settings.getPasswordCiphertext());
    requirePasswordFollowsTarget(settings, update.password(), host, port, username, encryption);
    requireCompleteWhenEnabled(update.enabled(), host, port, fromAddress);

    Map<String, Object> before = auditState(settings);
    String previousPasswordCiphertext = settings.getPasswordCiphertext();
    settings.replaceSettings(
        update.enabled(),
        host,
        port,
        username,
        passwordCiphertext,
        encryption,
        fromAddress,
        fromName,
        actorUserId);
    repository.save(settings);

    Map<String, Object> after = auditState(settings);
    AuditSecretChange.put(
        after, "password", previousPasswordCiphertext, settings.getPasswordCiphertext());
    // Nothing changed: no entry, the same rule reset() follows - an audit trail full of no-ops is
    // an audit trail nobody reads.
    if (!before.equals(after)) {
      recorder.accept(before, after);
    }
    eventPublisher.publishEvent(new MailSettingsChangedEvent());
    return settings;
  }

  /**
   * Writes the outcome of a send attempt to the stored row; called by {@link MailService} only.
   *
   * <p><b>Its own short transaction</b> ({@code REQUIRES_NEW}): joining the caller's would hold a
   * lock on the single {@code mail_settings} row for the rest of a business transaction - every
   * account creation would queue behind the previous one, and a {@code PUT} of the settings behind
   * all of them.
   *
   * <p><b>The price, named:</b> on a request thread this briefly holds a <em>second</em> pooled
   * connection next to the caller's, out of a fixed pool of 10 ({@code
   * spring.datasource.hikari.maximum-pool-size}). The scope is one {@code SELECT} plus one {@code
   * UPDATE} on a single row, and it runs once per sent mail - not per request - which is why the
   * trade is acceptable here and would not be for a longer unit of work.
   *
   * <p>Both failure directions are accepted: fails the inner transaction, the send stands and only
   * its status is lost - {@link MailService} swallows that and logs a warning, the caller still
   * gets its {@code Sent}; fails the outer one, the status row keeps an attempt whose business
   * transaction rolled back, which is correct, because the mail did go out.
   *
   * <p>Only the status columns are written, so settings saved at the same time stay as saved.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordSendOutcome(Instant at, String failureReason) {
    if (failureReason == null) {
      repository.recordSuccess(at);
    } else {
      repository.recordFailure(at, MailSettings.truncate(failureReason));
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onSettingsChanged(MailSettingsChangedEvent event) {
    rebuildSnapshot();
  }

  @Override
  public Set<String> invalidationTopics() {
    return Set.of(INVALIDATION_TOPIC);
  }

  /** The next read rebuilds the snapshot, and with it the transport. */
  @Override
  public void invalidateAll() {
    snapshotGeneration.incrementAndGet();
    snapshot.set(null);
  }

  /**
   * Drops the process-local snapshot so the next read rebuilds it from the database. For tests that
   * share this bean across classes. Tests outside this package reach it through {@code
   * io.opaa.mail.MailTestSupport}.
   */
  void resetCaches() {
    snapshot.set(null);
  }

  /** A rebuild that a drop overtook is returned but not kept: it may have read the old row. */
  private MailSettingsSnapshot rebuildSnapshot() {
    long before = snapshotGeneration.get();
    MailSettingsSnapshot rebuilt = toSnapshot(requireRow());
    snapshot.set(rebuilt);
    if (snapshotGeneration.get() != before) {
      snapshot.compareAndSet(rebuilt, null);
    }
    return rebuilt;
  }

  private MailSettingsSnapshot toSnapshot(MailSettings settings) {
    return new MailSettingsSnapshot(
        settings.isEnabled(),
        settings.getHost(),
        settings.getPort(),
        settings.getUsername(),
        settingsEncryptor.decrypt(settings.getPasswordCiphertext()),
        settings.getEncryption(),
        settings.getFromAddress(),
        settings.getFromName());
  }

  /**
   * A stored password belongs to the server, port, account and encryption it was entered for: if
   * any of them changes while the password would be kept, the change is refused on {@code password}
   * - the password is entered again or cleared, never sent to the new target.
   */
  private static void requirePasswordFollowsTarget(
      MailSettings stored,
      String submitted,
      String host,
      Integer port,
      String username,
      MailEncryption encryption) {
    boolean kept = submitted == null || PASSWORD_MASK.equals(submitted);
    if (!kept || stored.getPasswordCiphertext() == null) {
      return;
    }
    if (!MailSettingsRules.sameTarget(
        stored.getHost(),
        stored.getPort(),
        stored.getUsername(),
        stored.getEncryption(),
        host,
        port,
        username,
        encryption)) {
      throw FieldValidationException.of(
          "password",
          MailSettingsRules.PASSWORD_REQUIRED,
          MailSettingsRules.PASSWORD_FOLLOWS_TARGET);
    }
  }

  /** The lengths the columns and the request schema allow, each refused on its own field. */
  private static void requireWithinBounds(MailSettingsUpdate update) {
    List<FieldError> errors =
        MailSettingsRules.lengthErrors(
            "",
            update.host(),
            update.username(),
            update.password(),
            update.fromAddress(),
            update.fromName());
    if (!errors.isEmpty()) {
      throw new FieldValidationException("Eine Angabe des Mailversands ist zu lang.", errors);
    }
  }

  /**
   * The three-way password rule from {@link MailSettingsUpdate}: unchanged for {@code null} or the
   * mask, cleared for the empty string, replaced otherwise.
   */
  private String resolvePassword(String submitted, String storedCiphertext) {
    if (submitted == null || PASSWORD_MASK.equals(submitted)) {
      return storedCiphertext;
    }
    if (submitted.isEmpty()) {
      return null;
    }
    return settingsEncryptor.encrypt(submitted);
  }

  private static void requireCompleteWhenEnabled(
      boolean enabled, String host, Integer port, String fromAddress) {
    if (!enabled) {
      return;
    }
    if (host == null) {
      throw new ValidationException(MailSettingsRules.HOST_MISSING);
    }
    if (port == null) {
      throw new ValidationException(MailSettingsRules.PORT_MISSING);
    }
    if (fromAddress == null) {
      throw new ValidationException(MailSettingsRules.FROM_ADDRESS_MISSING);
    }
  }

  private static Integer validatedPort(Integer port) {
    MailSettingsRules.portProblem(port)
        .ifPresent(
            problem -> {
              throw new ValidationException(problem);
            });
    return port;
  }

  private static String validatedAddress(String address) {
    MailSettingsRules.fromAddressProblem(address)
        .ifPresent(
            problem -> {
              throw new ValidationException(problem);
            });
    return address;
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
        .type(AuditEventType.MAIL_SETTINGS_CHANGED)
        .object(
            AuditObjectType.SYSTEM_SETTING,
            UUID.nameUUIDFromBytes(SETTINGS_OBJECT_ID.getBytes(StandardCharsets.UTF_8)),
            SETTINGS_OBJECT_LABEL)
        .before(before)
        .after(after)
        .outcome(AuditOutcome.SUCCESS);
  }

  /**
   * Never the password - only whether one is set, mirroring {@code LlmModelService#auditState}; a
   * replaced or removed one adds {@code passwordChange} ({@link AuditSecretChange}).
   */
  private static Map<String, Object> auditState(MailSettings settings) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("enabled", settings.isEnabled());
    state.put("host", orDash(settings.getHost()));
    state.put("port", settings.getPort() == null ? "-" : settings.getPort());
    state.put("username", orDash(settings.getUsername()));
    state.put("passwordSet", settings.getPasswordCiphertext() != null);
    state.put("encryption", settings.getEncryption().name());
    state.put("fromAddress", orDash(settings.getFromAddress()));
    state.put("fromName", orDash(settings.getFromName()));
    return state;
  }

  private static String orDash(String value) {
    return StringUtils.hasText(value) ? value : "-";
  }

  private MailSettings requireRow() {
    return repository
        .findSingleton()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "mail_settings has no row with id="
                        + MailSettings.SINGLETON_ID
                        + " - the baseline should have created it"));
  }
}
