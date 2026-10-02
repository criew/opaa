package io.opaa.directory.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.OidcProviderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The summary a disclosure entry carries: groups and distinct persons named, never the names. */
class DirectorySyncReportDisclosureTest {

  private final AuditEventRecorder recorder = mock(AuditEventRecorder.class);
  private final OidcProviderRepository providerRepository = mock(OidcProviderRepository.class);
  private final DirectorySyncReportDisclosure disclosure =
      new DirectorySyncReportDisclosure(recorder, providerRepository);

  private final UUID organizationId = UUID.randomUUID();
  private final UUID actorId = UUID.randomUUID();
  private final UUID providerId = UUID.randomUUID();

  @Test
  void countsGroupsWithNamedMembersAndEachPersonOnceAcrossAllLists() {
    when(providerRepository.findById(providerId)).thenReturn(Optional.empty());
    UserRef anna = new UserRef(UUID.randomUUID(), "Anna Amsel");
    UserRef bert = new UserRef(UUID.randomUUID(), "Bert Buchfink");
    UserRef carl = new UserRef(UUID.randomUUID(), "Carl Clever");
    SyncReport report =
        report(
            List.of(
                new MembershipChange("dir-1", "Referat 50", List.of(anna), List.of(bert)),
                new MembershipChange("dir-2", "Referat 52", List.of(anna), List.of()),
                new MembershipChange("dir-3", "Referat 53", List.of(), List.of())),
            List.of(bert),
            List.of(carl));

    disclosure.recordIfNamed(
        organizationId,
        actorId,
        providerId,
        DirectorySyncReportDisclosure.Channel.RUN,
        null,
        report);

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(recorder).recordUserAction(captor.capture());
    AuditEvent event = captor.getValue();
    assertThat(event.eventType()).isEqualTo(AuditEventType.DIRECTORY_SYNC_REPORT_READ);
    assertThat(event.actorUserId()).isEqualTo(actorId);
    assertThat(event.objectType()).isEqualTo(AuditObjectType.DIRECTORY_SYNC_RUN);
    assertThat(event.objectId()).isEqualTo(providerId);
    assertThat(event.after())
        .containsEntry("channel", "RUN")
        .containsEntry("groupCount", 2)
        .containsEntry("personCount", 3)
        .doesNotContainKey("planId");
    assertThat(event.after().toString() + event.objectLabel())
        .doesNotContain("Anna")
        .doesNotContain("Bert")
        .doesNotContain("Carl");
  }

  @Test
  void aReportNamingNobodyWritesNothing() {
    SyncReport report =
        report(
            List.of(new MembershipChange("dir-1", "Referat 50", List.of(), List.of())),
            List.of(),
            List.of());

    disclosure.recordIfNamed(
        organizationId,
        actorId,
        providerId,
        DirectorySyncReportDisclosure.Channel.PENDING_PLAN,
        UUID.randomUUID(),
        report);

    verify(recorder, never()).recordUserAction(any());
  }

  private static SyncReport report(
      List<MembershipChange> changes, List<UserRef> locked, List<UserRef> withheld) {
    return new SyncReport(
        DirectorySyncOutcome.APPLIED,
        Instant.parse("2026-10-02T04:00:00Z"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        changes,
        locked,
        List.of(),
        withheld,
        0,
        0,
        0,
        0.0,
        0.3,
        "Bericht");
  }
}
