package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.DataCollectionDisclosureService.Format;
import io.opaa.compliance.InstanceDisclosureAssembler;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A disclosure that fails to assemble is logged as a failure and the failure reaches the caller.
 */
class DataCollectionDisclosureServiceTest {

  @Test
  void anExportThatFailsToAssembleIsLoggedAsAFailure() {
    InstanceDisclosureAssembler assembler = mock(InstanceDisclosureAssembler.class);
    AuditRetentionSettingsService retention = mock(AuditRetentionSettingsService.class);
    AuditEventRecorder recorder = mock(AuditEventRecorder.class);
    when(retention.currentRetentionMonths()).thenReturn(36);
    IllegalStateException broken = new IllegalStateException("manifest unreadable");
    when(assembler.assemble(any(), any())).thenThrow(broken);
    DataCollectionDisclosureService service =
        new DataCollectionDisclosureService(assembler, retention, recorder);

    assertThatThrownBy(
            () ->
                service.disclose(
                    UUID.randomUUID(), UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, Format.JSON))
        .isSameAs(broken);

    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(recorder).recordUserAction(event.capture());
    assertThat(event.getValue().eventType())
        .isEqualTo(AuditEventType.DATA_COLLECTION_DISCLOSURE_EXPORTED);
    assertThat(event.getValue().outcome()).isEqualTo(AuditOutcome.FAILURE);
  }
}
