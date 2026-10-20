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
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The order of the audit log is the order of the database clock, one for every instance of the
 * installation (ADR-0053, Entscheidung 9).
 */
class AuditLogServiceTest {

  private static final Instant DATABASE_NOW = Instant.parse("2026-10-19T08:00:00.123456Z");

  private final AuditLogRepository repository = mock(AuditLogRepository.class);
  private final AuditLogService service = new AuditLogService(repository, () -> DATABASE_NOW);

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
  void anEntryWithoutStampIsRefusedAtPersist() {
    assertThatThrownBy(() -> entry().onCreate()).isInstanceOf(IllegalStateException.class);
  }

  private static AuditLogEntry entry() {
    return AuditLogEntry.withoutSubject(
        UUID.randomUUID(),
        ActorKind.USER,
        "system",
        AuditEventType.values()[0],
        AuditObjectType.values()[0],
        "object",
        "Objekt",
        null,
        null,
        AuditOutcome.SUCCESS,
        null,
        null);
  }
}
