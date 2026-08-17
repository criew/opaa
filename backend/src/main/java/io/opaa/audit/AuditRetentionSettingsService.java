package io.opaa.audit;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the single, system-wide audit retention period (#395,
 * docs/features/security-and-compliance.md#aufbewahrung). This is the only path in this codebase
 * that writes {@link AuditRetentionSettings#getRetentionMonths()} - the database grant backs that
 * up (migration 023: the application account has no write access to {@code
 * audit_retention_settings.last_cutoff}, and this class never touches it).
 */
@Service
public class AuditRetentionSettingsService {

  private static final Logger log = LoggerFactory.getLogger(AuditRetentionSettingsService.class);

  /**
   * 1 year - the specification's non-negotiable floor
   * (docs/features/security-and-compliance.md#aufbewahrung).
   */
  public static final int MIN_RETENTION_MONTHS = 12;

  /** 10 years - the specification's non-negotiable ceiling. */
  public static final int MAX_RETENTION_MONTHS = 120;

  /** The default new deployments start with, and migration 023 seeds. */
  public static final int DEFAULT_RETENTION_MONTHS = 36;

  private static final String CONFIGURATION_OBJECT_ID = "audit-retention";

  private final AuditRetentionSettingsRepository repository;
  private final AuditEventRecorder auditEventRecorder;
  private final ObjectProvider<ContentRetentionProvider> contentRetentionProvider;

  public AuditRetentionSettingsService(
      AuditRetentionSettingsRepository repository,
      AuditEventRecorder auditEventRecorder,
      ObjectProvider<ContentRetentionProvider> contentRetentionProvider) {
    this.repository = repository;
    this.auditEventRecorder = auditEventRecorder;
    this.contentRetentionProvider = contentRetentionProvider;
  }

  /** The currently configured retention, in months. */
  @Transactional(readOnly = true)
  public int currentRetentionMonths() {
    return settingsRow().getRetentionMonths();
  }

  /**
   * Changes the configured retention period. Rejects anything outside {@link
   * #MIN_RETENTION_MONTHS}..{@link #MAX_RETENTION_MONTHS} with a German-language message before
   * ever writing (the database's own {@code chk_audit_retention_settings_months} is the backstop,
   * not the primary defense) - see #395 acceptance criteria: "Eine Konfiguration unterhalb eines
   * Jahres oder oberhalb von zehn Jahren wird abgewiesen".
   *
   * <p>Records the change itself via {@link AuditEventRecorder} with {@link
   * AuditEventType#AUDIT_LOG_CONFIGURATION_CHANGED} - #395 acceptance criteria: "Eine Friständerung
   * erzeugt selbst einen Protokolleintrag". No new event type was needed: {@code
   * AUDIT_LOG_CONFIGURATION_CHANGED} already exists (#391) and its own Javadoc already names "die
   * Protokollkonfiguration selbst" as covered.
   *
   * <p>Does not itself implement "Verkürzung wirkt nur nach vorn" - that guarantee lives entirely
   * in the {@code opaa_audit_delete_expired_partitions()} database function (migration 023), which
   * is the only writer of {@code last_cutoff} and the only place deletion actually happens; this
   * method only ever changes the configured target value, never triggers deletion itself.
   */
  @Transactional
  public AuditRetentionUpdateResult updateRetention(
      UUID organizationId, UUID actorUserId, int newRetentionMonths, String reason) {
    if (newRetentionMonths < MIN_RETENTION_MONTHS || newRetentionMonths > MAX_RETENTION_MONTHS) {
      throw new IllegalArgumentException(
          "Die Aufbewahrungsfrist muss zwischen "
              + MIN_RETENTION_MONTHS
              + " und "
              + MAX_RETENTION_MONTHS
              + " Monaten liegen (1 bis 10 Jahre), war aber "
              + newRetentionMonths);
    }

    int previousRetentionMonths = settingsRow().getRetentionMonths();
    // Deliberately not repository.save() on the read-only AuditRetentionSettings entity - see
    // AuditRetentionSettingsRepository#updateRetentionMonths's own Javadoc for why a
    // dirty-checked JPA save would fail against the real, restricted database grant.
    int updatedRows = repository.updateRetentionMonths(newRetentionMonths);
    if (updatedRows != 1) {
      throw new IllegalStateException(
          "audit_retention_settings update affected "
              + updatedRows
              + " rows, expected exactly 1 - the singleton row (id="
              + AuditRetentionSettings.SINGLETON_ID
              + ") is missing");
    }

    boolean inconsistent = isInconsistentWithContentRetention(newRetentionMonths);
    if (inconsistent) {
      log.warn(
          "Audit-Protokollfrist ({} Monate) ist kuerzer als die konfigurierte"
              + " Inhaltsaufbewahrung - inkonsistente Einstellung",
          newRetentionMonths);
    }

    auditEventRecorder.recordUserAction(
        organizationId,
        actorUserId,
        AuditEventType.AUDIT_LOG_CONFIGURATION_CHANGED,
        AuditObjectType.SYSTEM_SETTING,
        UUID.nameUUIDFromBytes(
            CONFIGURATION_OBJECT_ID.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        "Aufbewahrungsfrist des Protokolls",
        Map.of("retentionMonths", previousRetentionMonths),
        Map.of("retentionMonths", newRetentionMonths),
        AuditOutcome.SUCCESS,
        reason);

    return new AuditRetentionUpdateResult(newRetentionMonths, inconsistent);
  }

  /**
   * #395 acceptance criteria: "Eine Protokollfrist kürzer als die Inhaltsaufbewahrung erzeugt eine
   * Warnung". {@link ContentRetentionProvider} has no implementation yet (#216 is later scope, see
   * its own Javadoc) - {@code contentRetentionProvider} then resolves to nothing, and this always
   * returns {@code false}: there being no content retention configured yet is not itself an
   * inconsistency to warn about.
   */
  private boolean isInconsistentWithContentRetention(int retentionMonths) {
    ContentRetentionProvider provider = contentRetentionProvider.getIfAvailable();
    if (provider == null) {
      return false;
    }
    Optional<Integer> contentRetentionMonths = provider.contentRetentionMonths();
    return contentRetentionMonths.map(months -> retentionMonths < months).orElse(false);
  }

  private AuditRetentionSettings settingsRow() {
    return repository
        .findSingleton()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "audit_retention_settings hat keine Zeile mit id="
                        + AuditRetentionSettings.SINGLETON_ID
                        + " - migration 023 haette sie anlegen muessen"));
  }
}
