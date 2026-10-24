package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ActorKind;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The order of the audit log is the order of the database clock, one for every instance of the
 * installation (ADR-0053, Entscheidung 9).
 */
class AuditLogServiceTest {

  private static final Instant DATABASE_NOW = Instant.parse("2026-10-19T08:00:00.123456Z");

  private final AuditLogRepository repository = mock(AuditLogRepository.class);
  private final StaticListableBeanFactory beans = new StaticListableBeanFactory();
  private final AuditLogService service =
      new AuditLogService(
          repository, () -> DATABASE_NOW, beans.getBeanProvider(PersonalAuditObjects.class));

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
  void anEntryWithoutStampIsRefusedAtPersist() {
    assertThatThrownBy(() -> entry().onCreate()).isInstanceOf(IllegalStateException.class);
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
