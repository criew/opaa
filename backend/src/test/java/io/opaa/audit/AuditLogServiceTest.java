package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.ActorKind;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The order of the audit log is the order of the database clock, one for every instance of the
 * installation (ADR-0053, Entscheidung 9); its payload holds only the keys of the event catalog.
 */
class AuditLogServiceTest {

  private static final Instant DATABASE_NOW = Instant.parse("2026-10-19T08:00:00.123456Z");

  private final AuditLogRepository repository = mock(AuditLogRepository.class);
  private final StaticListableBeanFactory beans = new StaticListableBeanFactory();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final AuditLogService service = service(false);

  private AuditLogService service(boolean rejectUnlisted) {
    return new AuditLogService(
        repository,
        () -> DATABASE_NOW,
        beans.getBeanProvider(PersonalAuditObjects.class),
        beans.getBeanProvider(AuditPayloadKeys.class),
        new AuditPayloadProperties(rejectUnlisted),
        registry);
  }

  @Test
  void stampsTheEntryWithTheDatabaseClock() {
    when(repository.save(any(AuditLogEntry.class))).thenAnswer(call -> call.getArgument(0));
    AuditLogEntry entry = entry();

    service.record(entry);

    assertThat(entry.getRecordedAt()).isEqualTo(DATABASE_NOW);
  }

  @Test
  void anEntryIsNeverStampedTwice() {
    when(repository.save(any(AuditLogEntry.class))).thenAnswer(call -> call.getArgument(0));
    AuditLogEntry entry = entry();
    service.record(entry);

    assertThatThrownBy(() -> service.record(entry)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anObjectOfOnePersonIsWrittenUnderItsNeutralLabelWhateverTheCallerPassed() {
    // regression guard for ADR-0055, Entscheidung 2: a writer passing its own label for a
    // personal object must not put that label, nor a name, into the protocol
    when(repository.save(any(AuditLogEntry.class))).thenAnswer(call -> call.getArgument(0));
    beans.addBean(
        "personal",
        (PersonalAuditObjects)
            (type, ids) ->
                type == AuditObjectType.SPACE && ids.contains("space-of-a-person")
                    ? Map.of("space-of-a-person", AuditNeutralLabels.PERSONAL_SPACE)
                    : Map.of());
    AuditLogEntry personal = entry(AuditObjectType.SPACE, "space-of-a-person", "Meine Ablage");
    AuditLogEntry shared = entry(AuditObjectType.SPACE, "space-shared", "Bauamt");

    service.record(personal);
    service.record(shared);

    assertThat(personal.getObjectLabel()).isEqualTo(AuditNeutralLabels.PERSONAL_SPACE);
    assertThat(shared.getObjectLabel()).isEqualTo("Bauamt");
  }

  @Test
  void everyNewEntryCarriesTheSchemaVersion() {
    assertThat(entry().getSchemaVersion()).isEqualTo(AuditEventCatalog.SCHEMA_VERSION);
  }

  @Test
  void anUnlistedKeyIsDroppedAndTheListedOnesAreWritten() {
    when(repository.save(any(AuditLogEntry.class))).thenAnswer(call -> call.getArgument(0));
    AuditLogEntry entry =
        payloadEntry(
            AuditEventType.SPACE_MEMBER_ROLE_CHANGED,
            "{\"role\":\"MEMBER\",\"memberName\":\"Erika Muster\"}",
            "{\"memberName\":\"Erika Muster\"}");

    service.record(entry);

    assertThat(entry.getBefore()).isEqualTo("{\"role\":\"MEMBER\"}");
    assertThat(entry.getAfter()).isNull();
    verify(repository).save(entry);
    assertThat(dropped(AuditEventType.SPACE_MEMBER_ROLE_CHANGED)).isEqualTo(1.0);
  }

  @Test
  void anEntryWithOnlyListedKeysCountsNothing() {
    when(repository.save(any(AuditLogEntry.class))).thenAnswer(call -> call.getArgument(0));

    service.record(
        payloadEntry(AuditEventType.SPACE_MEMBER_ROLE_CHANGED, "{\"role\":\"MEMBER\"}", null));

    assertThat(registry.find(AuditLogService.DROPPED_METRIC).counter()).isNull();
  }

  @Test
  void aPayloadThatIsNoJsonObjectIsDropped() {
    when(repository.save(any(AuditLogEntry.class))).thenAnswer(call -> call.getArgument(0));
    AuditLogEntry entry =
        payloadEntry(AuditEventType.SPACE_MEMBER_ROLE_CHANGED, "[\"Erika Muster\"]", null);

    service.record(entry);

    assertThat(entry.getBefore()).isNull();
  }

  @Test
  void anUnlistedKeyRefusesTheWriteWhenRejectionIsOn() {
    AuditLogEntry entry =
        payloadEntry(
            AuditEventType.SPACE_MEMBER_ROLE_CHANGED,
            null,
            "{\"role\":\"OWNER\",\"memberName\":\"Erika Muster\"}");

    assertThatThrownBy(() -> service(true).record(entry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("memberName")
        .hasMessageContaining("SPACE_MEMBER_ROLE_CHANGED")
        .hasMessageNotContaining("Erika");
    verify(repository, never()).save(any());
    assertThat(registry.find(AuditLogService.DROPPED_METRIC).counter()).isNull();
  }

  @Test
  void aKeyADerivedSourceNamesIsListed() {
    when(repository.save(any(AuditLogEntry.class))).thenAnswer(call -> call.getArgument(0));
    beans.addBean(
        "derived",
        new AuditPayloadKeys() {
          @Override
          public AuditEventType eventType() {
            return AuditEventType.PRIVATE_LIBRARY_ERASED;
          }

          @Override
          public Set<String> keys() {
            return Set.of("chatSourcesRedacted");
          }
        });
    AuditLogEntry entry =
        payloadEntry(
            AuditEventType.PRIVATE_LIBRARY_ERASED,
            "{\"cause\":\"OWNER\",\"chatSourcesRedacted\":\"NONE\"}",
            null);

    service(true).record(entry);

    assertThat(entry.getBefore()).contains("chatSourcesRedacted");
  }

  @Test
  void anEntryWithoutStampIsRefusedAtPersist() {
    assertThatThrownBy(() -> entry().onCreate()).isInstanceOf(IllegalStateException.class);
  }

  private double dropped(AuditEventType type) {
    return registry
        .get(AuditLogService.DROPPED_METRIC)
        .tag("event_type", type.name())
        .counter()
        .count();
  }

  private static AuditLogEntry payloadEntry(AuditEventType type, String before, String after) {
    return AuditLogEntry.withoutSubject(
        UUID.randomUUID(),
        ActorKind.USER,
        "system",
        type,
        AuditObjectType.SPACE,
        "object",
        "Objekt",
        before,
        after,
        AuditOutcome.SUCCESS,
        null,
        null);
  }

  private static AuditLogEntry entry() {
    return entry(AuditObjectType.values()[0], "object", "Objekt");
  }

  private static AuditLogEntry entry(AuditObjectType objectType, String objectId, String label) {
    return AuditLogEntry.withoutSubject(
        UUID.randomUUID(),
        ActorKind.USER,
        "system",
        AuditEventType.values()[0],
        objectType,
        objectId,
        label,
        null,
        null,
        AuditOutcome.SUCCESS,
        null,
        null);
  }
}
