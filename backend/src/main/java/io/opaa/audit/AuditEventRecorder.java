package io.opaa.audit;

import io.opaa.api.types.ActorKind;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import java.util.Map;
import java.util.Objects;
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
   * Records an event caused by a person ({@link AuditEvent.Builder#actor}), with no affected rights
   * subject - e.g. a space or library being created. See {@link #recordUserActionOnSubject} for the
   * sibling that carries one. {@code event} must have been built with {@link
   * AuditEvent.Builder#actor} and no {@link AuditEvent.Builder#subject}.
   */
  public void recordUserAction(AuditEvent event) {
    UUID actorUserId = requireUserActor(event);
    requireNoSubject(event, "recordUserAction");
    requireNoCorrelationRef(event, "recordUserAction");
    String actorRef = pseudonymService.pseudonymFor(actorUserId, event.organizationId()).toString();
    auditLogService.record(
        AuditLogEntry.withoutSubject(
            event.organizationId(),
            ActorKind.USER,
            actorRef,
            event.eventType(),
            event.objectType(),
            event.objectId().toString(),
            event.objectLabel(),
            toJson(event.before()),
            toJson(event.after()),
            event.outcome(),
            event.reason(),
            null));
  }

  /**
   * Records an event caused by a person ({@link AuditEvent.Builder#actor}) that additionally names
   * the affected rights subject - a user (pseudonymised the same way the actor is) or a group
   * (referenced by its plain id, since a group is not a person and needs no pseudonymisation).
   * {@code event} must have been built with both {@link AuditEvent.Builder#actor} and {@link
   * AuditEvent.Builder#subject}.
   */
  public void recordUserActionOnSubject(AuditEvent event) {
    UUID actorUserId = requireUserActor(event);
    UUID subjectId = Objects.requireNonNull(event.subjectId(), "subject");
    AuditSubjectKind subjectKind = Objects.requireNonNull(event.subjectKind(), "subject");
    requireNoCorrelationRef(event, "recordUserActionOnSubject");
    String actorRef = pseudonymService.pseudonymFor(actorUserId, event.organizationId()).toString();
    String subjectRef =
        subjectKind == AuditSubjectKind.USER
            ? pseudonymService.pseudonymFor(subjectId, event.organizationId()).toString()
            : subjectId.toString();
    auditLogService.record(
        AuditLogEntry.withSubject(
            event.organizationId(),
            ActorKind.USER,
            actorRef,
            event.eventType(),
            event.objectType(),
            event.objectId().toString(),
            event.objectLabel(),
            subjectKind,
            subjectRef,
            toJson(event.before()),
            toJson(event.after()),
            event.outcome(),
            event.reason(),
            null));
  }

  /**
   * Records an event with no acting person - a directory synchronisation run, which {@link
   * io.opaa.library.PermissionHistoryService}'s writers already treat the same way ("a sync run has
   * no acting user"). {@link AuditEvent.Builder#actorRef} is a fixed, non-pseudonymised label
   * identifying the process, not a per-run or per-organization value - there is no person behind it
   * to protect. {@code event} must have been built with {@link AuditEvent.Builder#actorRef}; {@link
   * AuditEvent.Builder#subject} is optional.
   */
  public void recordSystemProcessAction(AuditEvent event) {
    String actorRef = Objects.requireNonNull(event.actorRef(), "actorRef");
    if (event.subjectKind() == null) {
      auditLogService.record(
          AuditLogEntry.withoutSubject(
              event.organizationId(),
              ActorKind.SYSTEM_PROCESS,
              actorRef,
              event.eventType(),
              event.objectType(),
              event.objectId().toString(),
              event.objectLabel(),
              toJson(event.before()),
              toJson(event.after()),
              event.outcome(),
              event.reason(),
              event.correlationRef()));
      return;
    }
    UUID subjectId = Objects.requireNonNull(event.subjectId(), "subject");
    String subjectRef =
        event.subjectKind() == AuditSubjectKind.USER
            ? pseudonymService.pseudonymFor(subjectId, event.organizationId()).toString()
            : subjectId.toString();
    auditLogService.record(
        AuditLogEntry.withSubject(
            event.organizationId(),
            ActorKind.SYSTEM_PROCESS,
            actorRef,
            event.eventType(),
            event.objectType(),
            event.objectId().toString(),
            event.objectLabel(),
            event.subjectKind(),
            subjectRef,
            toJson(event.before()),
            toJson(event.after()),
            event.outcome(),
            event.reason(),
            event.correlationRef()));
  }

  private static UUID requireUserActor(AuditEvent event) {
    return Objects.requireNonNull(event.actorUserId(), "actor");
  }

  /**
   * A subject silently dropped would be the compliance-log equivalent of a swallowed field: the
   * caller believed it was recording who was affected, and nothing about the resulting row says
   * otherwise. Rejects instead of ignoring so a caller that meant {@code recordUserActionOnSubject}
   * finds out at the call it made, not by an absent column later.
   */
  private static void requireNoSubject(AuditEvent event, String methodName) {
    if (event.subjectKind() != null || event.subjectId() != null) {
      throw new IllegalArgumentException(
          methodName
              + " does not carry a subject - use recordUserActionOnSubject for an event built"
              + " with AuditEvent.Builder#subject");
    }
  }

  /**
   * {@code correlationRef} only ever reaches a column via {@link #recordSystemProcessAction} - a
   * caller setting it on a user-action event has almost always mixed up which of the two builder
   * paths it meant, and silently ignoring the field would hide that mistake instead of failing the
   * call that made it.
   */
  private static void requireNoCorrelationRef(AuditEvent event, String methodName) {
    if (event.correlationRef() != null) {
      throw new IllegalArgumentException(
          methodName + " does not carry a correlationRef - only recordSystemProcessAction does");
    }
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
