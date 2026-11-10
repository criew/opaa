package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.observability.RequestIds;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

/**
 * An audit entry written while a request is served carries the request as its correlation ref when
 * nothing else names one; an operation's own ref wins. The ref never leaves for the SIEM.
 */
class AuditRequestCorrelationTest {

  private static final String ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  private final AuditLogService auditLogService = mock(AuditLogService.class);
  private final AuditActorPseudonymService pseudonymService =
      mock(AuditActorPseudonymService.class);
  private final AuditEventRecorder recorder =
      new AuditEventRecorder(auditLogService, pseudonymService);
  private final UUID organizationId = UUID.randomUUID();
  private final UUID actor = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    when(pseudonymService.pseudonymFor(actor, organizationId)).thenReturn(UUID.randomUUID());
    MDC.put(RequestIds.MDC_KEY, ID);
  }

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void aUserActionWithoutOwnRefCarriesTheRequest() {
    recorder.recordUserAction(event().build());

    assertThat(recorded().getCorrelationRef()).isEqualTo("request:" + ID);
  }

  @Test
  void aUserActionOnASubjectCarriesTheRequest() {
    recorder.recordUserActionOnSubject(
        event().subject(AuditSubjectKind.GROUP, UUID.randomUUID()).build());

    assertThat(recorded().getCorrelationRef()).isEqualTo("request:" + ID);
  }

  @Test
  void theOwnRefOfAnOperationWins() {
    recorder.recordUserAction(event().correlationRef("settings-import:1").build());

    assertThat(recorded().getCorrelationRef()).isEqualTo("settings-import:1");
  }

  @Test
  void aBoundOperationWinsOverTheRequest() {
    AuditCorrelation.within(
        "settings-import:2",
        () -> {
          recorder.recordUserAction(event().build());
          return null;
        });

    assertThat(recorded().getCorrelationRef()).isEqualTo("settings-import:2");
  }

  @Test
  void aReadOfTheProtocolCarriesTheRequest() {
    recorder.recordAuditLogAccess(
        organizationId, actor, Map.of("accessPath", "by-object"), AuditOutcome.SUCCESS, "Prüfung");

    assertThat(recorded().getCorrelationRef()).isEqualTo("request:" + ID);
  }

  @Test
  void outsideARequestNoRefIsInvented() {
    MDC.clear();

    recorder.recordUserAction(event().build());

    assertThat(recorded().getCorrelationRef()).isNull();
  }

  // The correlation ref - and with it the request id - is no field of the SIEM export, neither in
  // the record it formats nor in the query that reads the protocol for it; the rendered message is
  // checked in SiemExportServiceIntegrationTest.
  @Test
  void theSiemExportCarriesNoCorrelationRef() throws Exception {
    assertThat(
            Arrays.stream(AuditExportEntry.class.getRecordComponents())
                .map(RecordComponent::getName))
        .containsExactly(
            "eventId",
            "recordedAt",
            "organizationId",
            "eventType",
            "objectType",
            "objectId",
            "objectLabel",
            "outcome",
            "schemaVersion",
            "seal");
    String service =
        Files.readString(Path.of("src/main/java/io/opaa/audit/SiemExportService.java"));
    assertThat(service).doesNotContain("correlation");
  }

  private AuditEvent.Builder event() {
    return AuditEvent.builder()
        .organizationId(organizationId)
        .actor(actor)
        .type(AuditEventType.SPACE_CREATED)
        .object(AuditObjectType.SPACE, UUID.randomUUID(), "Team")
        .outcome(AuditOutcome.SUCCESS);
  }

  private AuditLogEntry recorded() {
    ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
    verify(auditLogService).record(captor.capture());
    return captor.getValue();
  }
}
