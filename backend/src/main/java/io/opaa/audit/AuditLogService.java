package io.opaa.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.common.DatabaseClock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write interface for services recording an audit entry ("so gebaut, dass ein Ereignis nicht
 * verloren geht, wenn der auslösende Vorgang zurückgerollt wird bzw. dass es dann auch nicht
 * geschrieben wird").
 *
 * <p><b>Chosen transaction behaviour: the entry is written in the caller's own transaction, not a
 * separate one.</b> {@link #record} is {@code @Transactional} with the default propagation, so a
 * call made from within an ambient transaction simply joins it; a rolled-back triggering operation
 * rolls the audit entry back with it, and a committed one commits it with it. Without an ambient
 * transaction, reading the clock and the insert share one transaction of their own. This is
 * deliberately chosen over a separate, always-committing transaction: an audit entry for an action
 * that itself never took effect would be actively misleading, e.g. "grant revoked" recorded while
 * the revoke itself never committed. A rejected action that genuinely happened is recorded with
 * {@link AuditOutcome#DENIED} or {@link AuditOutcome#FAILURE} by the calling service as part of its
 * own successful flow, not implied by a leftover row from a rolled-back attempt.
 *
 * <p>Verified by {@code AuditLogServiceIntegrationTest}, against a real transaction manager and
 * real Postgres, not a mocked one: committing the surrounding transaction persists the entry,
 * rolling it back leaves no trace of it.
 *
 * <p>{@code recordedAt} comes from the {@link DatabaseClock}: the order of the log is one order for
 * every instance of the installation, not the order of their host clocks (ADR-0053, Entscheidung
 * 9). It is read inside the inserting transaction, so it never lies before that transaction's
 * start, which the settled limit of {@link SiemExportService} relies on.
 *
 * <p>{@code before}/{@code after} carry only the keys {@link AuditEventCatalog} lists for the event
 * type: an unlisted key is dropped, logged as an error and counted in {@value #DROPPED_METRIC} by
 * event type (registered at 0 for every type on start, so an alert needs only its increase), or,
 * with {@link AuditPayloadProperties#rejectUnlisted()}, refuses the write and with it the caller's
 * transaction.
 */
@Service
public class AuditLogService {

  private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

  static final String DROPPED_METRIC = "opaa.audit.payload.dropped";

  private final AuditLogRepository auditLogRepository;
  private final DatabaseClock databaseClock;
  private final ObjectProvider<PersonalAuditObjects> personalObjects;
  private final ObjectProvider<AuditPayloadKeys> derivedPayloadKeys;
  private final AuditPayloadProperties payloadProperties;
  private final Map<AuditEventType, Counter> dropped = new EnumMap<>(AuditEventType.class);

  public AuditLogService(
      AuditLogRepository auditLogRepository,
      DatabaseClock databaseClock,
      ObjectProvider<PersonalAuditObjects> personalObjects,
      ObjectProvider<AuditPayloadKeys> derivedPayloadKeys,
      AuditPayloadProperties payloadProperties,
      MeterRegistry meterRegistry) {
    this.auditLogRepository = auditLogRepository;
    this.databaseClock = databaseClock;
    this.personalObjects = personalObjects;
    this.derivedPayloadKeys = derivedPayloadKeys;
    this.payloadProperties = payloadProperties;
    for (AuditEventType type : AuditEventType.values()) {
      dropped.put(
          type,
          Counter.builder(DROPPED_METRIC)
              .description(
                  "Audit entries written without payload keys the event catalog does not list")
              .tag("event_type", type.name())
              .register(meterRegistry));
    }
  }

  /**
   * Persists {@code entry}. Participates in the caller's ambient transaction if one is open (see
   * class Javadoc); if none is open, the clock reading and the write commit together in a
   * transaction of their own. An object that belongs to one person is written under its neutral
   * label, whatever label the caller passed ({@link PersonalAuditObjects}).
   */
  @Transactional
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
    dropped.get(entry.getEventType()).increment();
    entry.replacePayload(
        AuditEventCatalog.listedOnly(listed, entry.getBefore()),
        AuditEventCatalog.listedOnly(listed, entry.getAfter()));
  }
}
