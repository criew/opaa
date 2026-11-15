package io.opaa.permission;

import io.opaa.api.types.AuditEventType;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.settings.RetentionSetting;
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
  static final UUID SETTINGS_OBJECT_ID =
      RetentionSetting.objectId("permission_history_retention_settings");

  private final PermissionHistoryRetentionSettingsRepository repository;
  private final RetentionSetting setting;

  PermissionHistoryRetentionService(
      PermissionHistoryRetentionSettingsRepository repository,
      AuditEventRecorder auditEventRecorder) {
    this.repository = repository;
    this.setting =
        new RetentionSetting(
            auditEventRecorder,
            "permission_history_retention_settings",
            "Nur die Administration darf die Aufbewahrungshöchstdauer der Rechtehistorie einsehen"
                + " und ändern",
            AuditEventType.PERMISSION_HISTORY_RETENTION_CHANGED,
            "Aufbewahrungshöchstdauer der Rechtehistorie",
            new RetentionSetting.Bounds(
                PermissionHistoryRetentionSettings.MIN_RETENTION_MONTHS,
                PermissionHistoryRetentionSettings.MAX_RETENTION_MONTHS,
                "Monaten",
                " (1 bis 10 Jahre)"));
  }

  /**
   * How far the deletion has got - every Stichtag before it lies outside the retention and cannot
   * be answered from the history any more. Deliberately without an actor, unlike {@link #read}:
   * this is the boundary of what an Auskunft can state, not the configured period, and the reading
   * path needs it on every call to tell "no access" from "no longer on record" (see {@link
   * PermissionHistoryService#readableAssetIdsAsOf}). Empty before the first pass.
   */
  @Transactional(readOnly = true)
  public Optional<Instant> retentionCutoff() {
    return Optional.ofNullable(settingsRow().getLastCutoff());
  }

  /**
   * Since when every way an account became unusable is on record in the account-state history;
   * empty if the installation does not know. Like {@link #retentionCutoff} a boundary of what an
   * Auskunft can state, so without an actor.
   */
  @Transactional(readOnly = true)
  public Optional<Instant> accountStateCompleteSince() {
    return Optional.ofNullable(settingsRow().getAccountStateCompleteSince());
  }

  @Transactional(readOnly = true)
  public PermissionHistoryRetentionSettings read(CurrentUser actor) {
    setting.requireAdmin(actor);
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
    setting.requireAdmin(actor);
    setting.requireWithin("Die Aufbewahrungshöchstdauer der Rechtehistorie", months);
    int previous = settingsRow().getRetentionMonths();
    setting.change(
        actor,
        Map.of("retentionMonths", previous),
        Map.of("retentionMonths", months),
        () -> repository.updateRetentionMonths(months));
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
                        + " - the baseline should have seeded it"));
  }
}
