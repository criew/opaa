package io.opaa.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.Capability;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.permission.CapabilityService;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A directory that fails to assemble is audited as {@code FAILURE} and handed out to nobody. */
class AgentDirectoryServiceTest {

  private final AgentSwitch agentSwitch = mock(AgentSwitch.class);
  private final CapabilityService capabilityService = mock(CapabilityService.class);
  private final AgentDirectoryAssembler assembler = mock(AgentDirectoryAssembler.class);
  private final AuditEventRecorder recorder = mock(AuditEventRecorder.class);
  private final AgentDirectoryService service =
      new AgentDirectoryService(
          agentSwitch, capabilityService, assembler, recorder, Clock.systemUTC());

  @Test
  void aFailedAssemblyIsAuditedAsFailureAndRethrown() {
    CurrentUser caller = CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.USER, "X");
    when(agentSwitch.isEnabled()).thenReturn(true);
    when(capabilityService.hasCapability(caller, Capability.VIEW_AGENT_DIRECTORY)).thenReturn(true);
    when(assembler.assemble(any())).thenThrow(new IllegalStateException("database gone"));

    assertThatThrownBy(() -> service.exportCsv(caller))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("database gone");

    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(recorder).recordUserAction(event.capture());
    assertThat(event.getValue().eventType()).isEqualTo(AuditEventType.AGENT_DIRECTORY_ACCESSED);
    assertThat(event.getValue().outcome()).isEqualTo(AuditOutcome.FAILURE);
    assertThat(event.getValue().after()).containsEntry("form", "CSV");
  }
}
