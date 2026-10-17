package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.DataCollectionDisclosureService.Format;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.compliance.InstanceDisclosure.ReportingPeriod;
import io.opaa.compliance.InstanceDisclosureAssembler;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A disclosure that fails to assemble is logged as a failure and the failure reaches the caller;
 * every logged retrieval names its reporting period, and a period that is not made of whole months
 * is refused before anything is read.
 */
class DataCollectionDisclosureServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-17T08:00:00Z"), ZoneOffset.UTC);

  private final InstanceDisclosureAssembler assembler = mock(InstanceDisclosureAssembler.class);
  private final AuditRetentionSettingsService retention = mock(AuditRetentionSettingsService.class);
  private final AuditIncidentScopeCaseReport caseReport = mock(AuditIncidentScopeCaseReport.class);
  private final AuditEventRecorder recorder = mock(AuditEventRecorder.class);
  private final DataCollectionDisclosureService service =
      new DataCollectionDisclosureService(assembler, retention, caseReport, recorder, CLOCK);

  @Test
  void anExportThatFailsToAssembleIsLoggedAsAFailure() {
    when(retention.currentRetentionMonths()).thenReturn(36);
    IllegalStateException broken = new IllegalStateException("manifest unreadable");
    when(assembler.assemble(any(), any())).thenThrow(broken);

    assertThatThrownBy(
            () ->
                service.disclose(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    SystemRole.SYSTEM_ADMIN,
                    Format.JSON,
                    null,
                    null))
        .isSameAs(broken);

    AuditEvent event = recordedEvent();
    assertThat(event.eventType()).isEqualTo(AuditEventType.DATA_COLLECTION_DISCLOSURE_EXPORTED);
    assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
  }

  @Test
  void theEventNamesTheReportingPeriodAskedFor() {
    UUID organization = UUID.randomUUID();
    when(retention.currentRetentionMonths()).thenReturn(36);

    service.disclose(
        organization,
        UUID.randomUUID(),
        SystemRole.SYSTEM_ADMIN,
        Format.MARKDOWN,
        LocalDate.parse("2026-03-01"),
        LocalDate.parse("2026-04-30"));

    verify(caseReport)
        .report(
            organization,
            new ReportingPeriod(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-04-30")));
    assertThat(recordedEvent().after())
        .isEqualTo(
            Map.of("format", "MARKDOWN", "periodFrom", "2026-03-01", "periodTo", "2026-04-30"));
  }

  @Test
  void withoutAPeriodTheEventNamesTheDefaultOne() {
    assertThatThrownBy(
            () ->
                service.disclose(
                    UUID.randomUUID(), UUID.randomUUID(), SystemRole.USER, Format.JSON, null, null))
        .isInstanceOf(AccessDeniedException.class);

    AuditEvent event = recordedEvent();
    assertThat(event.outcome()).isEqualTo(AuditOutcome.DENIED);
    assertThat(event.after())
        .isEqualTo(Map.of("format", "JSON", "periodFrom", "2025-01-01", "periodTo", "2026-12-31"));
    verifyNoInteractions(caseReport, assembler);
  }

  @Test
  void aPeriodThatCutsAMonthIsRefusedBeforeAnythingIsRead() {
    assertThatThrownBy(
            () ->
                service.disclose(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    SystemRole.SYSTEM_ADMIN,
                    Format.JSON,
                    LocalDate.parse("2026-03-15"),
                    null))
        .isInstanceOf(ValidationException.class);

    verifyNoInteractions(caseReport, assembler, recorder);
  }

  private AuditEvent recordedEvent() {
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(recorder).recordUserAction(event.capture());
    return event.getValue();
  }
}
