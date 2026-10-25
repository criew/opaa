package io.opaa.library;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.registry.SourceConnectorRegistry;
import io.opaa.indexing.spi.FullSyncRhythm;
import io.opaa.indexing.spi.SourceConnectorDescriptor;
import io.opaa.knowledge.SourceType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the system administration does with the installation-wide rhythm of the full sync: read it
 * for every source type that has one and set or reset it for one type. Every change is an audit
 * event with the value before and after (ADR-0060, Entscheidung 11).
 */
@Service
public class FullSyncRhythmAdministration {

  private static final String ADMIN_ONLY =
      "Nur die Systemverwaltung darf den Vollabgleich-Rhythmus der Quellarten einsehen und ändern";

  private static final String UNKNOWN = "Diese Quellart hat keinen Vollabgleich-Rhythmus";

  private static final String OUT_OF_RANGE =
      "Der Vollabgleich-Rhythmus muss zwischen "
          + FullSyncRhythm.MIN_DAYS
          + " und "
          + FullSyncRhythm.MAX_DAYS
          + " Tagen liegen";

  private final SourceConnectorRegistry connectors;
  private final FullSyncRhythm rhythm;
  private final AuditEventRecorder auditEventRecorder;

  public FullSyncRhythmAdministration(
      SourceConnectorRegistry connectors,
      FullSyncRhythm rhythm,
      AuditEventRecorder auditEventRecorder) {
    this.connectors = connectors;
    this.rhythm = rhythm;
    this.auditEventRecorder = auditEventRecorder;
  }

  /**
   * The rhythm of one source type: the days in force, the factory default and whether the
   * administration set its own.
   */
  public record Rhythm(
      SourceType type,
      String displayName,
      int intervalDays,
      int defaultIntervalDays,
      boolean overridden) {}

  /** Every source type with a full-sync rhythm, ordered by type key. */
  @Transactional(readOnly = true)
  public List<Rhythm> list(CurrentUser actor) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    return connectors.descriptors().stream()
        .filter(descriptor -> descriptor.fullSyncInterval() != null)
        .map(this::current)
        .toList();
  }

  /**
   * Sets the rhythm of {@code typeKey}; {@code null} returns to the factory default. A call that
   * changes nothing writes neither the setting nor an audit event.
   *
   * @throws NotFoundException for a type that is not registered or has no full-sync rhythm
   * @throws ValidationException for days outside the admissible range
   */
  @Transactional
  public Rhythm change(CurrentUser actor, String typeKey, Integer days) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    SourceConnectorDescriptor descriptor = descriptorWithRhythm(typeKey);
    if (days != null && (days < FullSyncRhythm.MIN_DAYS || days > FullSyncRhythm.MAX_DAYS)) {
      throw new ValidationException(OUT_OF_RANGE);
    }
    Rhythm before = current(descriptor);
    if (unchanged(before, days)) {
      return before;
    }
    rhythm.setDays(descriptor.type(), days);
    Rhythm after = current(descriptor);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.FULL_SYNC_RHYTHM_CHANGED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                UUID.nameUUIDFromBytes(
                    ("full_sync_rhythm_settings:" + descriptor.type().key())
                        .getBytes(StandardCharsets.UTF_8)),
                "Quellart " + descriptor.displayName())
            .before(payload(before))
            .after(payload(after))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return after;
  }

  private SourceConnectorDescriptor descriptorWithRhythm(String typeKey) {
    if (!SourceType.isKey(typeKey)) {
      throw new NotFoundException(UNKNOWN);
    }
    return connectors
        .find(SourceType.of(typeKey))
        .map(connector -> connector.descriptor())
        .filter(descriptor -> descriptor.fullSyncInterval() != null)
        .orElseThrow(() -> new NotFoundException(UNKNOWN));
  }

  /**
   * Whether setting {@code days} would leave {@code current} as it is; such a call writes nothing.
   */
  private static boolean unchanged(Rhythm current, Integer days) {
    return days == null
        ? !current.overridden()
        : current.overridden() && current.intervalDays() == days;
  }

  private Rhythm current(SourceConnectorDescriptor descriptor) {
    int factoryDays = FullSyncRhythm.wholeDays(descriptor.fullSyncInterval());
    return rhythm
        .administrationDays(descriptor.type())
        .map(
            days ->
                new Rhythm(descriptor.type(), descriptor.displayName(), days, factoryDays, true))
        .orElseGet(
            () ->
                new Rhythm(
                    descriptor.type(), descriptor.displayName(), factoryDays, factoryDays, false));
  }

  private static Map<String, Object> payload(Rhythm rhythm) {
    return Map.of(
        "sourceType",
        rhythm.type().key(),
        "intervalDays",
        rhythm.intervalDays(),
        "overridden",
        rhythm.overridden());
  }
}
