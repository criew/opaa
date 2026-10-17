package io.opaa.audit;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.SystemRole;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.compliance.InstanceDisclosure;
import io.opaa.compliance.InstanceDisclosure.ReportingPeriod;
import io.opaa.compliance.InstanceDisclosureAssembler;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
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
 * event cannot be written, nothing is handed out, and an export that fails to assemble is logged as
 * {@code FAILURE}. The event names format and reporting period, never the content. A period that
 * cannot be read is refused before the role check and not logged, like a malformed date.
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
  private final AuditIncidentScopeCaseReport caseReport;
  private final AuditEventRecorder auditEventRecorder;
  private final Clock clock;

  public DataCollectionDisclosureService(
      InstanceDisclosureAssembler assembler,
      AuditRetentionSettingsService retentionSettings,
      AuditIncidentScopeCaseReport caseReport,
      AuditEventRecorder auditEventRecorder,
      Clock clock) {
    this.assembler = assembler;
    this.retentionSettings = retentionSettings;
    this.caseReport = caseReport;
    this.auditEventRecorder = auditEventRecorder;
    this.clock = clock;
  }

  /**
   * The disclosure for {@code callerId} of {@code organizationId}, with the anlassbezogenen
   * Klärungen from {@code from} to {@code to}; either bound may be {@code null}, see {@link
   * IncidentScopeReportingPeriods#of}.
   *
   * @throws ValidationException when the period is not made of whole months or starts after it ends
   * @throws AccessDeniedException when {@code callerRole} is not {@link SystemRole#SYSTEM_ADMIN},
   *     after the refusal was logged
   */
  public InstanceDisclosure disclose(
      UUID organizationId,
      UUID callerId,
      SystemRole callerRole,
      Format format,
      LocalDate from,
      LocalDate to) {
    ReportingPeriod period = IncidentScopeReportingPeriods.of(from, to, LocalDate.now(clock));
    if (callerRole != SystemRole.SYSTEM_ADMIN) {
      record(organizationId, callerId, format, period, AuditOutcome.DENIED);
      throw new AccessDeniedException(ADMIN_ONLY);
    }
    InstanceDisclosure disclosure;
    try {
      disclosure =
          assembler.assemble(
              AuditProtocolDescription.of(
                  retentionSettings.currentRetentionMonths(),
                  caseReport.report(organizationId, period)),
              organizationId);
    } catch (RuntimeException e) {
      record(organizationId, callerId, format, period, AuditOutcome.FAILURE);
      throw e;
    }
    record(organizationId, callerId, format, period, AuditOutcome.SUCCESS);
    return disclosure;
  }

  private void record(
      UUID organizationId,
      UUID callerId,
      Format format,
      ReportingPeriod period,
      AuditOutcome outcome) {
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(callerId)
            .type(AuditEventType.DATA_COLLECTION_DISCLOSURE_EXPORTED)
            .object(AuditObjectType.SYSTEM_SETTING, OBJECT_ID, OBJECT_LABEL)
            .after(
                Map.of(
                    "format",
                    format.name(),
                    "periodFrom",
                    period.from().toString(),
                    "periodTo",
                    period.to().toString()))
            .outcome(outcome)
            .build());
  }
}
