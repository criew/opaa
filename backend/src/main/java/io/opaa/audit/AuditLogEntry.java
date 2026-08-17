package io.opaa.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One entry of the append-only audit trail (#391, decision #355, see
 * docs/features/security-and-compliance.md#der-protokollsatz): "wer, wann, was, an welchem Objekt,
 * mit welchem Ergebnis". Immutable by design - there is no update method and no setter beyond the
 * constructor, matching {@code AssetGrant}'s pattern of a client-generated {@code id} for an
 * otherwise similarly shaped entity. That is only the application-level half of "nur anfügend"; the
 * binding half is the database privilege restriction migration 017 applies to the underlying table,
 * verified independently by {@code Migration017AuditLogTest}.
 *
 * <p>{@code recordedAt} is set here rather than left to the database's {@code DEFAULT now()}: the
 * column is also the partitioning key of {@code audit_log}, and callers such as {@link
 * AuditLogService} need the resulting timestamp on the object they hold, not only in the row a
 * later read would have to fetch again.
 *
 * <p>{@code before}/{@code after} deliberately hold pre-serialized JSON text rather than a
 * structured type: the specification requires them "eng begrenzt auf das rechtlich Erhebliche", not
 * a full object dump, and what counts as relevant differs per {@link AuditEventType} - the calling
 * service, not this entity, decides what goes in.
 *
 * <p>{@code actorRef} and {@code subjectRef} (when the subject is a {@link AuditSubjectKind#USER})
 * are pseudonym ids from {@link AuditActorPseudonym}, not user ids - the whole point of keeping
 * that mapping in a separate, independently deletable table
 * (docs/features/security-and-compliance.md#unveränderlichkeit-und-löschrecht).
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry {

  @Id
  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "actor_kind", nullable = false, length = 30)
  private ActorKind actorKind;

  @Column(name = "actor_ref", nullable = false)
  private String actorRef;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 60)
  private AuditEventType eventType;

  @Enumerated(EnumType.STRING)
  @Column(name = "object_type", nullable = false, length = 30)
  private AuditObjectType objectType;

  @Column(name = "object_id", nullable = false)
  private String objectId;

  @Column(name = "object_label")
  private String objectLabel;

  @Enumerated(EnumType.STRING)
  @Column(name = "subject_kind", length = 10)
  private AuditSubjectKind subjectKind;

  @Column(name = "subject_ref")
  private String subjectRef;

  @Column(name = "before")
  private String before;

  @Column(name = "after")
  private String after;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", nullable = false, length = 10)
  private AuditOutcome outcome;

  @Column(name = "reason", length = 1000)
  private String reason;

  @Column(name = "correlation_ref")
  private String correlationRef;

  protected AuditLogEntry() {}

  private AuditLogEntry(
      UUID organizationId,
      ActorKind actorKind,
      String actorRef,
      AuditEventType eventType,
      AuditObjectType objectType,
      String objectId,
      String objectLabel,
      AuditSubjectKind subjectKind,
      String subjectRef,
      String before,
      String after,
      AuditOutcome outcome,
      String reason,
      String correlationRef) {
    this.eventId = UUID.randomUUID();
    this.organizationId = organizationId;
    this.actorKind = actorKind;
    this.actorRef = actorRef;
    this.eventType = eventType;
    this.objectType = objectType;
    this.objectId = objectId;
    this.objectLabel = objectLabel;
    this.subjectKind = subjectKind;
    this.subjectRef = subjectRef;
    this.before = before;
    this.after = after;
    this.outcome = outcome;
    this.reason = reason;
    this.correlationRef = correlationRef;
  }

  /**
   * Builds a new entry with no rights subject (e.g. a governance settings change) - {@code
   * subjectKind}/{@code subjectRef} stay null together, satisfying {@code chk_audit_log_subject}.
   */
  public static AuditLogEntry withoutSubject(
      UUID organizationId,
      ActorKind actorKind,
      String actorRef,
      AuditEventType eventType,
      AuditObjectType objectType,
      String objectId,
      String objectLabel,
      String before,
      String after,
      AuditOutcome outcome,
      String reason,
      String correlationRef) {
    return new AuditLogEntry(
        organizationId,
        actorKind,
        actorRef,
        eventType,
        objectType,
        objectId,
        objectLabel,
        null,
        null,
        before,
        after,
        outcome,
        reason,
        correlationRef);
  }

  /** Builds a new entry carrying the affected rights subject (a user or a group). */
  public static AuditLogEntry withSubject(
      UUID organizationId,
      ActorKind actorKind,
      String actorRef,
      AuditEventType eventType,
      AuditObjectType objectType,
      String objectId,
      String objectLabel,
      AuditSubjectKind subjectKind,
      String subjectRef,
      String before,
      String after,
      AuditOutcome outcome,
      String reason,
      String correlationRef) {
    if (subjectKind == null || subjectRef == null) {
      throw new IllegalArgumentException("subjectKind and subjectRef are both required here");
    }
    return new AuditLogEntry(
        organizationId,
        actorKind,
        actorRef,
        eventType,
        objectType,
        objectId,
        objectLabel,
        subjectKind,
        subjectRef,
        before,
        after,
        outcome,
        reason,
        correlationRef);
  }

  @PrePersist
  void onCreate() {
    if (recordedAt == null) {
      recordedAt = Instant.now();
    }
  }

  public UUID getEventId() {
    return eventId;
  }

  public Instant getRecordedAt() {
    return recordedAt;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public ActorKind getActorKind() {
    return actorKind;
  }

  public String getActorRef() {
    return actorRef;
  }

  public AuditEventType getEventType() {
    return eventType;
  }

  public AuditObjectType getObjectType() {
    return objectType;
  }

  public String getObjectId() {
    return objectId;
  }

  public String getObjectLabel() {
    return objectLabel;
  }

  public AuditSubjectKind getSubjectKind() {
    return subjectKind;
  }

  public String getSubjectRef() {
    return subjectRef;
  }

  public String getBefore() {
    return before;
  }

  public String getAfter() {
    return after;
  }

  public AuditOutcome getOutcome() {
    return outcome;
  }

  public String getReason() {
    return reason;
  }

  public String getCorrelationRef() {
    return correlationRef;
  }
}
