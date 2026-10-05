package io.opaa.connection.log;

import io.opaa.api.types.ConnectionLogEventType;
import io.opaa.audit.AuditAccessGate;
import io.opaa.audit.AuditEventRecorder;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * The only read path into the connection log. Every read of entries ({@link #find}) passes {@link
 * AuditAccessGate}: role {@code AUDITOR}, mandatory reason, bounded time range and paging, and
 * writes {@code CONNECTION_LOG_ACCESSED} into the audit log, the rejected attempt included. No
 * filter by person. {@link #loggedProfiles} names profiles only, without reason, bound or record;
 * its caller checks the role.
 */
@Service
public class ConnectionLogQueryService {

  private static final Sort RECORDED_AT_ASC = Sort.by(Sort.Direction.ASC, "recordedAt");

  private final ConnectionLogRepository repository;
  private final AuditAccessGate gate;
  private final AuditEventRecorder eventRecorder;

  ConnectionLogQueryService(
      ConnectionLogRepository repository, AuditAccessGate gate, AuditEventRecorder eventRecorder) {
    this.repository = repository;
    this.gate = gate;
    this.eventRecorder = eventRecorder;
  }

  /** {@code eventType} and {@code profileId} are optional filters. */
  public record Query(
      Instant from,
      Instant to,
      ConnectionLogEventType eventType,
      UUID profileId,
      int page,
      int size) {}

  public Page<ConnectionLogEntry> find(
      UUID organizationId, UUID callerId, String reason, Query query) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accessPath", "connection-log");
    scope.put("from", str(query.from()));
    scope.put("to", str(query.to()));
    scope.put("eventType", str(query.eventType()));
    scope.put("profileId", str(query.profileId()));
    return gate.loggedAccess(
        organizationId,
        callerId,
        reason,
        (outcome, cappedReason) ->
            eventRecorder.recordConnectionLogAccess(
                organizationId, callerId, scope, outcome, cappedReason),
        () -> {
          gate.validateTimeRange(query.from(), query.to());
          return repository.findInRange(
              organizationId,
              query.from(),
              query.to(),
              query.eventType(),
              query.profileId(),
              gate.pageable(query.page(), query.size(), RECORDED_AT_ASC));
        });
  }

  /** The profiles the organization's log names, by their last logged name, sorted by it. */
  public List<LoggedProfile> loggedProfiles(UUID organizationId) {
    return repository.findLoggedProfiles(organizationId).stream()
        .map(row -> new LoggedProfile(row.getProfileId(), row.getName()))
        .sorted(Comparator.comparing(LoggedProfile::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /** One profile the log names, by the name of its newest entry. */
  public record LoggedProfile(UUID profileId, String name) {}

  private static String str(Object value) {
    return value == null ? null : value.toString();
  }
}
