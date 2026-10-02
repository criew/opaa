package io.opaa.directory.sync;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.OidcProvider;
import io.opaa.auth.OidcProviderRepository;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Records that the system administration received a report naming persons (ADR-0036, Entscheidung
 * 9: the administration may see who is in a group, and that it looked is on the record). One
 * summary entry per response, on the provider as object, with the number of groups and persons the
 * report named - never the names themselves. A report naming nobody writes nothing.
 */
@Service
public class DirectorySyncReportDisclosure {

  /** Which response carried the report. */
  public enum Channel {
    PENDING_PLAN,
    DRY_RUN,
    RUN,
    PLAN_CONFIRMATION
  }

  private final AuditEventRecorder auditEventRecorder;
  private final OidcProviderRepository providerRepository;

  public DirectorySyncReportDisclosure(
      AuditEventRecorder auditEventRecorder, OidcProviderRepository providerRepository) {
    this.auditEventRecorder = auditEventRecorder;
    this.providerRepository = providerRepository;
  }

  /**
   * Writes the entry if {@code report} names at least one person. {@code planId} is set for {@link
   * Channel#PENDING_PLAN} only. Must run before the report is handed out: a failing write then
   * fails the response instead of disclosing names without a record.
   */
  public void recordIfNamed(
      UUID organizationId,
      UUID actorUserId,
      UUID providerId,
      Channel channel,
      UUID planId,
      SyncReport report) {
    Set<UUID> persons = new HashSet<>();
    int groups = 0;
    for (MembershipChange change : report.membershipChanges()) {
      if (!change.added().isEmpty() || !change.removed().isEmpty()) {
        groups++;
      }
      addIds(persons, change.added());
      addIds(persons, change.removed());
    }
    addIds(persons, report.accountsLocked());
    addIds(persons, report.accountsUnlocked());
    addIds(persons, report.accountLocksWithheld());
    if (persons.isEmpty()) {
      return;
    }
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("providerId", providerId.toString());
    after.put("channel", channel.name());
    if (planId != null) {
      after.put("planId", planId.toString());
    }
    after.put("groupCount", groups);
    after.put("personCount", persons.size());
    String providerName =
        providerRepository.findById(providerId).map(OidcProvider::getDisplayName).orElse(null);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.DIRECTORY_SYNC_REPORT_READ)
            .object(
                AuditObjectType.DIRECTORY_SYNC_RUN,
                providerId,
                providerName == null
                    ? "Verzeichnisabgleich"
                    : "Verzeichnisabgleich (" + providerName + ")")
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private static void addIds(Set<UUID> persons, List<UserRef> refs) {
    for (UserRef ref : refs) {
      persons.add(ref.id());
    }
  }
}
