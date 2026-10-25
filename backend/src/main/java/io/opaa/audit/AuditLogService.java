package io.opaa.audit;

import io.opaa.api.types.AuditOutcome;
import io.opaa.common.DatabaseClock;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The write interface for services recording an audit entry ("so gebaut, dass ein Ereignis nicht
 * verloren geht, wenn der auslösende Vorgang zurückgerollt wird bzw. dass es dann auch nicht
 * geschrieben wird").
 *
 * <p><b>Chosen transaction behaviour: the entry is written in the caller's own transaction, not a
 * separate one.</b> {@link #record} carries no {@code @Transactional} of its own, so a call made
 * from within an ambient transaction simply joins it; a rolled-back triggering operation rolls the
 * audit entry back with it, and a committed one commits it with it. This is deliberately chosen
 * over a separate, always-committing transaction: an audit entry for an action that itself never
 * took effect would be actively misleading, e.g. "grant revoked" recorded while the revoke itself
 * never committed. A rejected action that genuinely happened is recorded with {@link
 * AuditOutcome#DENIED} or {@link AuditOutcome#FAILURE} by the calling service as part of its own
 * successful flow, not implied by a leftover row from a rolled-back attempt.
 *
 * <p>Verified by {@code AuditLogServiceIntegrationTest}, against a real transaction manager and
 * real Postgres, not a mocked one: committing the surrounding transaction persists the entry,
 * rolling it back leaves no trace of it.
 *
 * <p>{@code recordedAt} comes from the {@link DatabaseClock}: the order of the log is one order for
 * every instance of the installation, not the order of their host clocks (ADR-0053, Entscheidung
 * 9).
 *
 * <p>{@code before}/{@code after} carry only the keys {@link AuditEventCatalog} lists for the event
 * type: an unlisted key is dropped and logged as an error, or, with {@link
 * AuditPayloadProperties#rejectUnlisted()}, refuses the write and with it the caller's transaction.
 */
@Service
public class AuditLogService {

  private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

  private final AuditLogRepository auditLogRepository;
  private final DatabaseClock databaseClock;
  private final ObjectProvider<PersonalAuditObjects> personalObjects;
  private final ObjectProvider<AuditPayloadKeys> derivedPayloadKeys;
  private final AuditPayloadProperties payloadProperties;

  public AuditLogService(
      AuditLogRepository auditLogRepository,
      DatabaseClock databaseClock,
      ObjectProvider<PersonalAuditObjects> personalObjects,
      ObjectProvider<AuditPayloadKeys> derivedPayloadKeys,
      AuditPayloadProperties payloadProperties) {
    this.auditLogRepository = auditLogRepository;
    this.databaseClock = databaseClock;
    this.personalObjects = personalObjects;
    this.derivedPayloadKeys = derivedPayloadKeys;
    this.payloadProperties = payloadProperties;
  }

  /**
   * Persists {@code entry}. Participates in the caller's ambient transaction if one is open (see
   * class Javadoc); if none is open, the write commits immediately on its own, like any other plain
   * repository call in this codebase. An object that belongs to one person is written under its
   * neutral label, whatever label the caller passed ({@link PersonalAuditObjects}).
   */
  public AuditLogEntry record(AuditLogEntry entry) {
    for (PersonalAuditObjects port : personalObjects.orderedStream().toList()) {
      String neutral =
          port.personal(entry.getObjectType(), List.of(entry.getObjectId()))
              .get(entry.getObjectId());
      if (neutral != null) {
        entry.neutralizeObjectLabel(neutral);
        break;
      }
    }
    keepListedPayloadKeys(entry);
    entry.stampRecordedAt(databaseClock.now());
    return auditLogRepository.save(entry);
  }

  private void keepListedPayloadKeys(AuditLogEntry entry) {
    Set<String> listed =
        AuditEventCatalog.listedKeys(
            entry.getEventType(), derivedPayloadKeys.orderedStream().toList());
    Set<String> unlisted =
        AuditEventCatalog.unlistedKeys(listed, entry.getBefore(), entry.getAfter());
    if (unlisted.isEmpty()) {
      return;
    }
    if (payloadProperties.rejectUnlisted()) {
      throw new IllegalStateException(
          "Audit payload keys "
              + unlisted
              + " are not listed for "
              + entry.getEventType()
              + " in AuditEventCatalog");
    }
    log.error(
        "Dropped audit payload keys {} not listed for {} in AuditEventCatalog",
        unlisted,
        entry.getEventType());
    entry.replacePayload(
        AuditEventCatalog.listedOnly(listed, entry.getBefore()),
        AuditEventCatalog.listedOnly(listed, entry.getAfter()));
  }
}
