package io.opaa.audit;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The convenience entry point #392's services call to write a first-stage audit event - composes
 * {@link AuditActorPseudonymService} (actor/subject pseudonymisation) and {@link AuditLogService}
 * (the actual write) so no caller needs to repeat that wiring or hand-build {@code before}/{@code
 * after} JSON itself. Every {@code recordXxx} method except {@link #recordAuditLogAccess} holds no
 * {@code @Transactional} of its own: it simply delegates to {@link AuditLogService#record}, which
 * is what carries the "joins the caller's ambient transaction, never its own" guarantee (see that
 * class's Javadoc) - adding one here would only obscure that this class contributes nothing to it.
 *
 * <p><b>{@link #recordAuditLogAccess} is the one deliberate exception</b> (PR #450 review, finding
 * 5) - see its own Javadoc for why.
 *
 * <p>{@code before}/{@code after} are small {@link Map}s the caller builds inline (e.g. {@code
 * Map.of("role", role.name())}), serialised here with a locally-owned {@link JsonMapper} instance -
 * not the application's autoconfigured bean, so this class stays usable in the narrower Spring test
 * slices some of its callers are exercised under without pulling in Jackson's full
 * autoconfiguration. A {@code null} or empty map serialises to a {@code null} column, matching
 * {@link AuditLogEntry}'s own optional {@code before}/{@code after}.
 */
@Service
public class AuditEventRecorder {

  /**
   * {@code object_id} every #394 self-log entry carries - {@code audit_log} is a singleton per
   * organization, not an entity with its own id, so there is nothing more specific to name here
   * (see {@link #recordAuditLogAccess}).
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
   * The #394 self-log entry for one access attempt against {@code audit_log} itself - {@code
   * outcome} {@link AuditOutcome#SUCCESS} for a permitted, executed query, {@link AuditOutcome#
   * DENIED} for a rejected attempt (missing AUDITOR role, missing {@code reason}, invalid or
   * too-wide time range, an incident scope outside its approved bounds, ...), so a denied attempt
   * is recorded exactly like a successful one
   * (docs/features/security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt: "auch
   * der abgewiesene Versuch erzeugt einen Eintrag"). {@code scope} - the access path plus whatever
   * of object/event type, correlation ref, incident scope id and time range that path takes - is
   * the record's "Umfang der Abfrage"; there is no dedicated column for it, so it is serialised
   * into {@code after} the same way every other caller of this class serialises its own
   * before/after state. {@code reason} is the mandatory Anlass and goes into the entry's own {@code
   * reason} column - required by the caller ({@link io.opaa.audit.AuditQueryService#loggedAccess})
   * before this method is ever reached, not re-validated here.
   *
   * <p>{@code object_id} is the fixed {@link #AUDIT_LOG_SELF_OBJECT_ID}, not a per-query id: {@code
   * audit_log} itself is what was accessed, once per organization, regardless of which access path
   * or which rows the query touched - those live in {@code scope}/{@code after}, not in {@code
   * object_id}.
   *
   * <p><b>PR #450 review, finding 5 - the one deliberate exception to this class's (and {@link
   * AuditLogService}'s) "no transaction of its own" rule.</b> {@link
   * AuditQueryService#loggedAccess} is not itself {@code @Transactional}, and today nothing wraps
   * it in one either - which is the only reason a {@code DENIED} entry currently survives the
   * exception that triggered it (see that method's Javadoc). That is an invariant resting entirely
   * on every future caller happening to behave the same way; nothing enforces it. {@code
   * Propagation.NOT_SUPPORTED} closes that gap for this one write path: it suspends whatever
   * transaction (if any) is active on the calling thread for the duration of this method, so {@link
   * AuditLogService#record} always runs with no ambient transaction to join - exactly today's
   * behaviour, now guaranteed rather than incidental. A future {@code @Transactional} service that
   * embeds {@link AuditQueryService} can therefore still roll back everything else it did, but
   * never this entry.
   *
   * <p>{@code NOT_SUPPORTED} rather than {@code REQUIRES_NEW} deliberately - but not because of a
   * connection-count difference between the two (PR #450 re-review, nit 2: an earlier draft of this
   * Javadoc claimed {@code NOT_SUPPORTED} avoids holding a second connection; that is wrong and is
   * corrected here so it is not cited as precedent later). Spring's suspension mechanism ({@code
   * AbstractPlatformTransactionManager#doSuspend}) only unbinds the ambient transaction's resources
   * from the current thread; it does not return the ambient connection to the pool - that
   * connection stays checked out, held by the suspended resources holder, for as long as it is
   * suspended, under {@code NOT_SUPPORTED} exactly as it would under {@code REQUIRES_NEW}. Both
   * propagations therefore have two connections in play for the duration of this call: the
   * suspended ambient one and the one {@link AuditLogService#record}'s own default propagation
   * acquires to open and commit its short-lived transaction.
   *
   * <p>The actual reason for {@code NOT_SUPPORTED}: it adds no transactional semantics of its own -
   * this method simply stops being part of any transaction, and the commit that already happens
   * today ({@link AuditLogService#record}'s un-annotated default propagation, see that class's
   * Javadoc) is untouched. {@code REQUIRES_NEW} would instead make this method itself directly
   * responsible for beginning and committing a transaction - a second, explicit transaction
   * boundary to reason about, the same shape #280/#297/#299 (developer role contract, Transaktionen
   * section) all warn against, even though none of those three incidents is reproduced by this
   * particular call (it has no data dependency on anything the ambient transaction holds
   * uncommitted, and it is not itself reporting the ambient operation's success - it only ever
   * records that an access to {@code audit_log} was attempted, true regardless of what the ambient
   * transaction later does). {@code NOT_SUPPORTED} keeps this class exactly where it already was -
   * never itself opening a transaction - while still closing the "a future caller might join one"
   * gap. Verified by {@code
   * AuditQueryServiceIntegrationTest#theDeniedEntrySurvivesEvenWhenEmbeddedInARollingTransaction}
   * against a real transaction manager and real Postgres, with the call deliberately wrapped in a
   * rolled-back {@code TransactionTemplate} - not just the no-ambient-transaction case {@code
   * theDeniedEntrySurvivesTheRejectionThatTriggeredIt} already covered.
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
   * same person as the {@code subject} (e.g. {@code UserService#updateRole}'s {@code
   * SYSTEM_ADMIN_ROLE_GRANTED}/{@code _REVOKED}) can pass that one pseudonym as both {@code
   * objectId} and {@code subjectId}, instead of the real {@code userId} as {@code objectId}. Using
   * the real id there would place both the plain id and its pseudonym in the same row - trivially
   * reversing every other entry's pseudonymisation for that person, and (via {@code object_label})
   * surviving the account deletion that is supposed to make the log unattributable again (code
   * review of #392/#444).
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
