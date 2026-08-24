package io.opaa.audit;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Convenience entry point services call to write an audit event - composes {@link
 * AuditActorPseudonymService} (actor/subject pseudonymisation) and {@link AuditLogService} (the
 * actual write) so no caller needs to repeat that wiring. Every {@code recordXxx} method except
 * {@link #recordAuditLogAccess} holds no {@code @Transactional} of its own: it delegates to {@link
 * AuditLogService#record}, which joins the caller's ambient transaction (see that class's Javadoc).
 *
 * <p>{@code before}/{@code after} are small {@link Map}s the caller builds inline, serialised here
 * with a locally-owned {@link JsonMapper} instance rather than the application's autoconfigured
 * bean, so this class stays usable in narrower Spring test slices. A {@code null} or empty map
 * serialises to a {@code null} column, matching {@link AuditLogEntry}'s optional {@code
 * before}/{@code after}.
 */
@Service
public class AuditEventRecorder {

  /**
   * {@code object_id} every self-log entry carries - {@code audit_log} is a singleton per
   * organization, not an entity with its own id.
   */
  private static final String AUDIT_LOG_SELF_OBJECT_ID = "audit_log";

  private final AuditLogService auditLogService;
  private final AuditActorPseudonymService pseudonymService;
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  public AuditEventRecorder(
      AuditLogService auditLogService, AuditActorPseudonymService pseudonymService) {
    this.auditLogService = auditLogService;
    this.pseudonymService = pseudonymService;
  }

  /**
   * Records an event caused by a person ({@code actorUserId}), with no affected rights subject -
   * e.g. a space or library being created. See {@link #recordUserActionOnSubject} for the sibling
   * that carries one.
   */
  public void recordUserAction(
      UUID organizationId,
      UUID actorUserId,
      AuditEventType eventType,
      AuditObjectType objectType,
      UUID objectId,
      String objectLabel,
      Map<String, Object> before,
      Map<String, Object> after,
      AuditOutcome outcome,
      String reason) {
    String actorRef = pseudonymService.pseudonymFor(actorUserId, organizationId).toString();
    auditLogService.record(
        AuditLogEntry.withoutSubject(
            organizationId,
            ActorKind.USER,
            actorRef,
            eventType,
            objectType,
            objectId.toString(),
            objectLabel,
            toJson(before),
            toJson(after),
            outcome,
            reason,
            null));
  }

  /**
   * Records an event caused by a person ({@code actorUserId}) that additionally names the affected
   * rights subject - a user (pseudonymised the same way the actor is) or a group (referenced by its
   * plain id, since a group is not a person and needs no pseudonymisation).
   */
  public void recordUserActionOnSubject(
      UUID organizationId,
      UUID actorUserId,
      AuditEventType eventType,
      AuditObjectType objectType,
      UUID objectId,
      String objectLabel,
      AuditSubjectKind subjectKind,
      UUID subjectId,
      Map<String, Object> before,
      Map<String, Object> after,
      AuditOutcome outcome,
      String reason) {
    String actorRef = pseudonymService.pseudonymFor(actorUserId, organizationId).toString();
    String subjectRef =
        subjectKind == AuditSubjectKind.USER
            ? pseudonymService.pseudonymFor(subjectId, organizationId).toString()
            : subjectId.toString();
    auditLogService.record(
        AuditLogEntry.withSubject(
            organizationId,
            ActorKind.USER,
            actorRef,
            eventType,
            objectType,
            objectId.toString(),
            objectLabel,
            subjectKind,
            subjectRef,
            toJson(before),
            toJson(after),
            outcome,
            reason,
            null));
  }

  /**
   * Records an event with no acting person - a directory synchronisation run, which {@link
   * io.opaa.library.PermissionHistoryService}'s writers already treat the same way ("a sync run has
   * no acting user"). {@code actorRef} is a fixed, non-pseudonymised label identifying the process,
   * not a per-run or per-organization value - there is no person behind it to protect.
   */
  public void recordSystemProcessAction(
      UUID organizationId,
      String actorRef,
      AuditEventType eventType,
      AuditObjectType objectType,
      UUID objectId,
      String objectLabel,
      AuditSubjectKind subjectKind,
      UUID subjectId,
      Map<String, Object> before,
      Map<String, Object> after,
      AuditOutcome outcome,
      String reason,
      String correlationRef) {
    if (subjectKind == null) {
      auditLogService.record(
          AuditLogEntry.withoutSubject(
              organizationId,
              ActorKind.SYSTEM_PROCESS,
              actorRef,
              eventType,
              objectType,
              objectId.toString(),
              objectLabel,
              toJson(before),
              toJson(after),
              outcome,
              reason,
              correlationRef));
      return;
    }
    String subjectRef =
        subjectKind == AuditSubjectKind.USER
            ? pseudonymService.pseudonymFor(subjectId, organizationId).toString()
            : subjectId.toString();
    auditLogService.record(
        AuditLogEntry.withSubject(
            organizationId,
            ActorKind.SYSTEM_PROCESS,
            actorRef,
            eventType,
            objectType,
            objectId.toString(),
            objectLabel,
            subjectKind,
            subjectRef,
            toJson(before),
            toJson(after),
            outcome,
            reason,
            correlationRef));
  }

  /**
   * The self-log entry for one access attempt against {@code audit_log} itself. {@code outcome} is
   * {@link AuditOutcome#SUCCESS} for a permitted, executed query and {@link AuditOutcome#DENIED}
   * for a rejected attempt, so a denied attempt is recorded exactly like a successful one. {@code
   * scope} (access path plus whatever of object/event type, correlation ref, incident scope id and
   * time range that path takes) has no dedicated column and is serialised into {@code after}.
   * {@code reason} is required by the caller ({@link AuditQueryService#loggedAccess}) before this
   * method is ever reached, not re-validated here. {@code object_id} is the fixed {@link
   * #AUDIT_LOG_SELF_OBJECT_ID}, not a per-query id: {@code audit_log} itself is what was accessed,
   * once per organization, regardless of which rows the query touched.
   *
   * <p>This is the one method in this class carrying its own {@code @Transactional}. {@code
   * Propagation.NOT_SUPPORTED} suspends whatever transaction is active on the calling thread for
   * the duration of this method, so {@link AuditLogService#record} always runs with no ambient
   * transaction to join. A {@code @Transactional} service embedding {@link
   * AuditQueryService#loggedAccess} can therefore roll back everything else it did without losing
   * this entry.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void recordAuditLogAccess(
      UUID organizationId,
      UUID actorUserId,
      Map<String, Object> scope,
      AuditOutcome outcome,
      String reason) {
    String actorRef = pseudonymService.pseudonymFor(actorUserId, organizationId).toString();
    auditLogService.record(
        AuditLogEntry.withoutSubject(
            organizationId,
            ActorKind.USER,
            actorRef,
            AuditEventType.AUDIT_LOG_ACCESSED,
            AuditObjectType.AUDIT_LOG,
            AUDIT_LOG_SELF_OBJECT_ID,
            null,
            null,
            toJson(scope),
            outcome,
            reason,
            null));
  }

  /**
   * The pseudonym id for {@code userId} - exposed so a caller whose {@code object} <em>is</em> the
   * same person as the {@code subject} can pass that one pseudonym as both {@code objectId} and
   * {@code subjectId}, instead of the real {@code userId} as {@code objectId}. Using the real id
   * there would place both the plain id and its pseudonym in the same row, trivially reversing
   * every other entry's pseudonymisation for that person.
   */
  public UUID pseudonymFor(UUID userId, UUID organizationId) {
    return pseudonymService.pseudonymFor(userId, organizationId);
  }

  private String toJson(Map<String, Object> value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    return jsonMapper.writeValueAsString(value);
  }
}
