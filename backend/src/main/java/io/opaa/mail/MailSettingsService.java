package io.opaa.mail;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.MailEncryption;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.ValidationException;
import io.opaa.security.SettingsEncryptor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

/**
 * Reads and changes the systemwide SMTP configuration (#1536, ADR-0033 Entscheidung 10) and keeps
 * the process-local snapshot every send path reads.
 *
 * <p><b>The snapshot is the point.</b> {@link #snapshot()} answers from an {@link AtomicReference}
 * rather than from the database, so sending a mail opens no transaction and needs the settings
 * encryption key only when the snapshot is (re)built. It is rebuilt lazily on first use and on
 * every {@link MailSettingsChangedEvent} <em>after</em> its transaction committed - a change takes
 * effect in the same process, without a restart, and a rolled-back change never takes effect at
 * all. Process-local without distributed invalidation, per ADR-0021.
 *
 * <p><b>The password never leaves this class in clear other than inside the snapshot.</b> It is
 * stored encrypted by {@link SettingsEncryptor}, answered by the API as {@link #PASSWORD_MASK}, and
 * absent from the audit payload, which records only whether one is set - the convention {@code
 * LlmModelService} established for a model's access key.
 */
@Service
public class MailSettingsService {

  /**
   * What the API answers instead of the stored password, and what a caller sends back to say
   * "unchanged". Deliberately a value that cannot be a password anybody typed: three asterisks are
   * below every length minimum a mail server enforces.
   */
  public static final String PASSWORD_MASK = "***";

  /**
   * The {@code object_id} every mail-settings audit entry carries - the settings are a singleton
   * with a fixed id of 1, so the same {@code UUID.nameUUIDFromBytes} convention {@code
   * BrandingSettingsService} uses applies here.
   */
  private static final String CONFIGURATION_OBJECT_ID = "mail-settings";

  private static final String OBJECT_LABEL = "E-Mail-Versand";

  /**
   * Deliberately permissive: one {@code @}, no whitespace, a dot in the domain part. The mail
   * server is the authority on what it accepts, and a stricter pattern here would reject valid
   * internal addresses for no gain.
   */
  private static final Pattern MAIL_ADDRESS = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

  private static final int MIN_PORT = 1;
  private static final int MAX_PORT = 65535;

  private final MailSettingsRepository repository;
  private final SettingsEncryptor settingsEncryptor;
  private final AuditEventRecorder auditEventRecorder;
  private final ApplicationEventPublisher eventPublisher;

  private final AtomicReference<MailSettingsSnapshot> snapshot = new AtomicReference<>();
  private final AtomicReference<MailSendStatus> status = new AtomicReference<>();

  public MailSettingsService(
      MailSettingsRepository repository,
      SettingsEncryptor settingsEncryptor,
      AuditEventRecorder auditEventRecorder,
      ApplicationEventPublisher eventPublisher) {
    this.repository = repository;
    this.settingsEncryptor = settingsEncryptor;
    this.auditEventRecorder = auditEventRecorder;
    this.eventPublisher = eventPublisher;
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
    MailSettingsSnapshot current = snapshot.get();
    if (current == null) {
      current = rebuildSnapshot();
    }
    return current;
  }

  /** When mail last worked and when it last did not; read by {@link MailHealthIndicator}. */
  public MailSendStatus status() {
    MailSendStatus current = status.get();
    if (current == null) {
      current = rebuildStatus();
    }
    return current;
  }

  /**
   * Replaces every administrable field. Enabling requires a host, a port and a sender address:
   * "eingeschaltet, aber unvollständig" would report itself as configured and then skip every
   * message, which is exactly the failure this endpoint exists to make impossible.
   */
  @Transactional
  public MailSettings updateSettings(
      UUID organizationId, UUID actorUserId, MailSettingsUpdate update) {
    String host = trimmedOrNull(update.host());
    Integer port = validatedPort(update.port());
    String username = trimmedOrNull(update.username());
    String fromAddress = validatedAddress(trimmedOrNull(update.fromAddress()));
    String fromName = trimmedOrNull(update.fromName());
    MailEncryption encryption =
        update.encryption() == null ? MailEncryption.STARTTLS : update.encryption();

    MailSettings settings = requireRow();
    String passwordCiphertext =
        resolvePassword(update.password(), settings.getPasswordCiphertext());
    requireCompleteWhenEnabled(update.enabled(), host, port, fromAddress);

    Map<String, Object> before = auditState(settings);
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

    recordChange(organizationId, actorUserId, before, auditState(settings));
    eventPublisher.publishEvent(new MailSettingsChangedEvent());
    return settings;
  }

  /**
   * Writes the outcome of a send attempt to the stored row and to the in-memory status. Called by
   * {@link MailService} only.
   *
   * <p>Deliberately no transaction of its own: it joins whatever transaction the caller is in, so
   * no second connection is held while a business transaction runs (agents/roles/developer.md,
   * "Transaktionen"). The consequence is accepted and named: if the caller's transaction rolls back
   * after a mail went out, the durable status loses that attempt while the in-memory status - which
   * is what the health indicator reads - keeps it. Losing the record of a send that happened is the
   * harmless direction; reporting a send that did not happen is not.
   */
  @Transactional
  public void recordSendOutcome(Instant at, String failureReason) {
    MailSettings settings = requireRow();
    if (failureReason == null) {
      settings.recordSuccess(at);
    } else {
      settings.recordFailure(at, failureReason);
    }
    repository.save(settings);
    status.set(
        new MailSendStatus(
            settings.getLastSuccessAt(),
            settings.getLastFailureAt(),
            settings.getLastFailureReason()));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onSettingsChanged(MailSettingsChangedEvent event) {
    rebuildSnapshot();
  }

  private MailSettingsSnapshot rebuildSnapshot() {
    MailSettingsSnapshot rebuilt = toSnapshot(requireRow());
    snapshot.set(rebuilt);
    return rebuilt;
  }

  private MailSendStatus rebuildStatus() {
    MailSettings settings = requireRow();
    MailSendStatus rebuilt =
        new MailSendStatus(
            settings.getLastSuccessAt(),
            settings.getLastFailureAt(),
            settings.getLastFailureReason());
    status.set(rebuilt);
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
      throw new ValidationException("Ohne SMTP-Server kann der Versand nicht eingeschaltet werden");
    }
    if (port == null) {
      throw new ValidationException("Ohne Port kann der Versand nicht eingeschaltet werden");
    }
    if (fromAddress == null) {
      throw new ValidationException(
          "Ohne Absenderadresse kann der Versand nicht eingeschaltet werden");
    }
  }

  private static Integer validatedPort(Integer port) {
    if (port == null) {
      return null;
    }
    if (port < MIN_PORT || port > MAX_PORT) {
      throw new ValidationException(
          "Der Port muss zwischen " + MIN_PORT + " und " + MAX_PORT + " liegen");
    }
    return port;
  }

  private static String validatedAddress(String address) {
    if (address == null) {
      return null;
    }
    if (!MAIL_ADDRESS.matcher(address).matches()) {
      throw new ValidationException(
          "Die Absenderadresse muss eine E-Mail-Adresse sein, zum Beispiel opaa@example.org");
    }
    return address;
  }

  private static String trimmedOrNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
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
            .type(AuditEventType.MAIL_SETTINGS_CHANGED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                UUID.nameUUIDFromBytes(CONFIGURATION_OBJECT_ID.getBytes(StandardCharsets.UTF_8)),
                OBJECT_LABEL)
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /** Never the password - only whether one is set, mirroring {@code LlmModelService#auditState}. */
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
                        + " - changeset 011-mail-settings should have created it"));
  }
}
