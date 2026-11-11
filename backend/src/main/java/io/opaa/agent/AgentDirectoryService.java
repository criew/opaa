package io.opaa.agent;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.Capability;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.permission.CapabilityService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Hands out the agent directory to whoever holds {@link Capability#VIEW_AGENT_DIRECTORY}, the
 * system administration implicitly.
 *
 * <p><b>Every call is an audit event</b> ({@link AuditEventType#AGENT_DIRECTORY_ACCESSED}), a
 * refused one included, naming the form and never the content. A granted call is logged after the
 * directory was assembled and before it is handed out; when the event cannot be written, nothing is
 * handed out, and a directory that fails to assemble is logged as {@code FAILURE}. While agents are
 * switched off, the directory answers like an unknown address and nothing is logged.
 */
@Service
public class AgentDirectoryService {

  /** Fixed object id of every event of the directory; it is no entity with an id of its own. */
  public static final UUID OBJECT_ID =
      UUID.nameUUIDFromBytes("agent-directory".getBytes(StandardCharsets.UTF_8));

  private static final String OBJECT_LABEL = "Agentenverzeichnis";

  /** The form the directory is handed out in. */
  public enum Form {
    VIEW,
    CSV
  }

  /** The directory as a file. */
  public record DirectoryFile(String fileName, byte[] content) {}

  private final AgentSwitch agentSwitch;
  private final CapabilityService capabilityService;
  private final AgentDirectoryAssembler assembler;
  private final AuditEventRecorder auditEventRecorder;
  private final Clock clock;

  AgentDirectoryService(
      AgentSwitch agentSwitch,
      CapabilityService capabilityService,
      AgentDirectoryAssembler assembler,
      AuditEventRecorder auditEventRecorder,
      Clock clock) {
    this.agentSwitch = agentSwitch;
    this.capabilityService = capabilityService;
    this.assembler = assembler;
    this.auditEventRecorder = auditEventRecorder;
    this.clock = clock;
  }

  /** The directory for the screen. */
  public AgentDirectory read(CurrentUser caller) {
    return disclose(caller, Form.VIEW);
  }

  /** The directory as a semicolon-separated UTF-8 CSV file. */
  public DirectoryFile exportCsv(CurrentUser caller) {
    AgentDirectory directory = disclose(caller, Form.CSV);
    return new DirectoryFile(
        "agentenverzeichnis-" + LocalDate.now(clock) + ".csv",
        AgentDirectoryCsv.render(directory, clock.getZone()));
  }

  private AgentDirectory disclose(CurrentUser caller, Form form) {
    if (!agentSwitch.isEnabled()) {
      throw new NotFoundException("Agentenverzeichnis nicht gefunden");
    }
    if (!capabilityService.hasCapability(caller, Capability.VIEW_AGENT_DIRECTORY)) {
      record(caller, form, AuditOutcome.DENIED);
      throw new AccessDeniedException(
          CapabilityService.missing(Capability.VIEW_AGENT_DIRECTORY),
          CapabilityService.CAPABILITY_REQUIRED);
    }
    AgentDirectory directory;
    try {
      directory = assembler.assemble(caller);
    } catch (RuntimeException e) {
      record(caller, form, AuditOutcome.FAILURE);
      throw e;
    }
    record(caller, form, AuditOutcome.SUCCESS);
    return directory;
  }

  private void record(CurrentUser caller, Form form, AuditOutcome outcome) {
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(caller.organizationId())
            .actor(caller.id())
            .type(AuditEventType.AGENT_DIRECTORY_ACCESSED)
            .object(AuditObjectType.SYSTEM_SETTING, OBJECT_ID, OBJECT_LABEL)
            .after(Map.of("form", form.name()))
            .outcome(outcome)
            .build());
  }
}
