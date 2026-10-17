package io.opaa.audit;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.SystemRole;
import io.opaa.common.AccessDeniedException;
import io.opaa.compliance.InstanceDisclosure;
import io.opaa.compliance.InstanceDisclosureAssembler;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Hands out the disclosure of the data collection with the configuration export of this instance
 * ("Auskunft über die Datenerhebung", ADR-0052) to the system administration only.
 *
 * <p><b>Every call is an audit event</b> ({@link
 * AuditEventType#DATA_COLLECTION_DISCLOSURE_EXPORTED}), a refused one included - the role is
 * therefore checked here and not by {@code @PreAuthorize}, whose refusal would never reach this
 * class. A granted export is logged after it was assembled and before it is handed out; when the
 * event cannot be written, nothing is handed out. The event names the format, never the content.
 */
@Service
public class DataCollectionDisclosureService {

  /** Fixed object id of every event of the disclosure; it is no entity with an id of its own. */
  public static final UUID OBJECT_ID =
      UUID.nameUUIDFromBytes("data-collection-disclosure".getBytes(StandardCharsets.UTF_8));

  private static final String OBJECT_LABEL = "Auskunft über die Datenerhebung";

  private static final String ADMIN_ONLY =
      "Nur die Systemverwaltung darf die Auskunft über die Datenerhebung abrufen";

  /** The form the disclosure is handed out in. */
  public enum Format {
    JSON,
    MARKDOWN
  }

  private final InstanceDisclosureAssembler assembler;
  private final AuditRetentionSettingsService retentionSettings;
  private final AuditEventRecorder auditEventRecorder;

  public DataCollectionDisclosureService(
      InstanceDisclosureAssembler assembler,
      AuditRetentionSettingsService retentionSettings,
      AuditEventRecorder auditEventRecorder) {
    this.assembler = assembler;
    this.retentionSettings = retentionSettings;
    this.auditEventRecorder = auditEventRecorder;
  }

  /**
   * The disclosure for {@code callerId} of {@code organizationId}.
   *
   * @throws AccessDeniedException when {@code callerRole} is not {@link SystemRole#SYSTEM_ADMIN},
   *     after the refusal was logged
   */
  public InstanceDisclosure disclose(
      UUID organizationId, UUID callerId, SystemRole callerRole, Format format) {
    if (callerRole != SystemRole.SYSTEM_ADMIN) {
      record(organizationId, callerId, format, AuditOutcome.DENIED);
      throw new AccessDeniedException(ADMIN_ONLY);
    }
    InstanceDisclosure disclosure =
        assembler.assemble(AuditProtocolDescription.of(retentionSettings.currentRetentionMonths()));
    record(organizationId, callerId, format, AuditOutcome.SUCCESS);
    return disclosure;
  }

  private void record(UUID organizationId, UUID callerId, Format format, AuditOutcome outcome) {
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(callerId)
            .type(AuditEventType.DATA_COLLECTION_DISCLOSURE_EXPORTED)
            .object(AuditObjectType.SYSTEM_SETTING, OBJECT_ID, OBJECT_LABEL)
            .after(Map.of("format", format.name()))
            .outcome(outcome)
            .build());
  }
}
