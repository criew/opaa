package io.opaa.permission;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the maximum retention period of the rights history (ADR-0036, Entscheidung 8) -
 * the only path in this codebase that writes {@code retention_months}. The period is bounded
 * 12..120 months; there is deliberately no method to switch the deletion off, and none can be added
 * without also removing {@link PermissionHistoryRetentionScheduler}, whose run is unconditional.
 *
 * <p>Every change is a governance event ({@link AuditEventType#PERMISSION_HISTORY_RETENTION_CHANGED
 * }) with the previous and the new value, so the personnel council's extract shows when the period
 * of the personal history sources was changed and by whom.
 */
@Service
public class PermissionHistoryRetentionService {

  /**
   * Fixed, well-known object id of the settings singleton in the audit trail - it is a system
   * setting, not an entity with an id of its own.
   */
  private static final UUID SETTINGS_OBJECT_ID =
      UUID.nameUUIDFromBytes(
          "permission_history_retention_settings".getBytes(StandardCharsets.UTF_8));

  private final PermissionHistoryRetentionSettingsRepository repository;
  private final AuditEventRecorder auditEventRecorder;

  PermissionHistoryRetentionService(
      PermissionHistoryRetentionSettingsRepository repository,
      AuditEventRecorder auditEventRecorder) {
    this.repository = repository;
    this.auditEventRecorder = auditEventRecorder;
  }

  /**
   * How far the deletion has got - every Stichtag before it lies outside the retention and cannot
   * be answered from the history any more. Deliberately without an actor, unlike {@link #read}:
   * this is the boundary of what an Auskunft can state, not the configured period, and the reading
   * path #1822 builds needs it on every call to tell "no access" from "no longer on record" (see
   * {@link PermissionHistoryService#readableAssetIdsAsOf}). Empty before the first pass.
   */
  @Transactional(readOnly = true)
  public Optional<Instant> retentionCutoff() {
    return Optional.ofNullable(settingsRow().getLastCutoff());
  }

  @Transactional(readOnly = true)
  public PermissionHistoryRetentionSettings read(CurrentUser actor) {
    requireSystemAdmin(actor);
    return settingsRow();
  }

  /**
   * Changes the configured period. Rejects anything outside {@link
   * PermissionHistoryRetentionSettings#MIN_RETENTION_MONTHS}..{@link
   * PermissionHistoryRetentionSettings#MAX_RETENTION_MONTHS} before writing; the database's own
   * check constraint is the backstop, not the primary defense.
   *
   * <p>Does not itself delete anything, and a shortening does not take effect at once: how far one
   * deletion pass may advance is decided entirely by {@link
   * PermissionHistoryRetentionDeletionService}.
   */
  @Transactional
  public PermissionHistoryRetentionSettings updateRetentionMonths(CurrentUser actor, int months) {
    requireSystemAdmin(actor);
    if (months < PermissionHistoryRetentionSettings.MIN_RETENTION_MONTHS
        || months > PermissionHistoryRetentionSettings.MAX_RETENTION_MONTHS) {
      throw new ValidationException(
          "Die Aufbewahrungshöchstdauer der Rechtehistorie muss zwischen "
              + PermissionHistoryRetentionSettings.MIN_RETENTION_MONTHS
              + " und "
              + PermissionHistoryRetentionSettings.MAX_RETENTION_MONTHS
              + " Monaten liegen (1 bis 10 Jahre)");
    }

    int previous = settingsRow().getRetentionMonths();
    int updatedRows = repository.updateRetentionMonths(months);
    if (updatedRows != 1) {
      throw new IllegalStateException(
          "permission_history_retention_settings update affected "
              + updatedRows
              + " rows, expected exactly 1 - the singleton row (id="
              + PermissionHistoryRetentionSettings.SINGLETON_ID
              + ") is missing");
    }

    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.PERMISSION_HISTORY_RETENTION_CHANGED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                SETTINGS_OBJECT_ID,
                "Aufbewahrungshöchstdauer der Rechtehistorie")
            .before(Map.of("retentionMonths", previous))
            .after(Map.of("retentionMonths", months))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return settingsRow();
  }

  private PermissionHistoryRetentionSettings settingsRow() {
    return repository
        .findSingleton()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "permission_history_retention_settings has no row with id="
                        + PermissionHistoryRetentionSettings.SINGLETON_ID
                        + " - changelog 045 should have seeded it"));
  }

  private void requireSystemAdmin(CurrentUser actor) {
    if (!actor.isSystemAdmin()) {
      throw new AccessDeniedException(
          "Nur die Administration darf die Aufbewahrungshöchstdauer der Rechtehistorie einsehen und"
              + " ändern");
    }
  }
}
