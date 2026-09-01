package io.opaa.diagnosticaccess;

import io.opaa.api.types.DiagnosticTargetKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * One execution of a search diagnosis in a foreign rights context, with exactly the fields
 * Leitplanke (f) requires: acting person, target context, time, test question, number and
 * identifiers of the displayed hits, the rights snapshot used, and the justification whenever a
 * person's context was assumed.
 *
 * <p>Immutable like {@code AuditLogEntry}, and for the same reason and by the same means: no
 * setter, {@link #isNew()} always {@code true} so Spring Data uses {@code persist} rather than
 * {@code merge}, and a database privilege model (ADR-0015) that leaves the application account only
 * {@code INSERT} and {@code SELECT}.
 *
 * <p>{@code actorRef} and - for a {@link DiagnosticTargetKind#USER} target - {@code targetRef} are
 * pseudonym ids from {@code AuditActorPseudonym}, not user ids, sharing the audit trail's
 * separately deletable re-identification table. For a {@link
 * DiagnosticTargetKind#PERMISSION_PROFILE} target, {@code targetRef} is the profile's label: a
 * profile belongs to nobody and needs no protection.
 *
 * <p>What is deliberately <b>not</b> here: the diagnosis result. Leitplanke (j) forbids storing it;
 * {@code hitRefs} are the identifiers of what was shown, not the titles, snippets or scores.
 */
@Entity
@Table(name = "diagnostic_context_log")
public class DiagnosticContextLogEntry implements Persistable<UUID> {

  @Id
  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "actor_ref", nullable = false)
  private String actorRef;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_kind", nullable = false, length = 20)
  private DiagnosticTargetKind targetKind;

  @Column(name = "target_ref", nullable = false)
  private String targetRef;

  @Column(name = "test_question", nullable = false, length = 2000)
  private String testQuestion;

  @Column(name = "hit_count", nullable = false)
  private int hitCount;

  @Column(name = "hit_refs", nullable = false)
  private String hitRefs;

  @Column(name = "permission_snapshot", nullable = false)
  private String permissionSnapshot;

  @Column(name = "justification", length = 1000)
  private String justification;

  protected DiagnosticContextLogEntry() {}

  /**
   * @throws IllegalArgumentException if a {@link DiagnosticTargetKind#USER} target carries no
   *     justification - the application-level half of {@code
   *     chk_diagnostic_context_log_justification}.
   */
  public DiagnosticContextLogEntry(
      UUID organizationId,
      String actorRef,
      DiagnosticTargetKind targetKind,
      String targetRef,
      String testQuestion,
      int hitCount,
      String hitRefs,
      String permissionSnapshot,
      String justification) {
    if (targetKind == DiagnosticTargetKind.USER
        && (justification == null || justification.isBlank())) {
      throw new IllegalArgumentException("a USER-context diagnosis must carry a justification");
    }
    this.eventId = UUID.randomUUID();
    this.organizationId = organizationId;
    this.actorRef = actorRef;
    this.targetKind = targetKind;
    this.targetRef = targetRef;
    this.testQuestion = testQuestion;
    this.hitCount = hitCount;
    this.hitRefs = hitRefs;
    this.permissionSnapshot = permissionSnapshot;
    this.justification = justification;
  }

  @PrePersist
  void onCreate() {
    if (recordedAt == null) {
      recordedAt = Instant.now();
    }
  }

  @Override
  public UUID getId() {
    return eventId;
  }

  /** Always {@code true} - see the class Javadoc; this entity is never an update candidate. */
  @Override
  public boolean isNew() {
    return true;
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

  public String getActorRef() {
    return actorRef;
  }

  public DiagnosticTargetKind getTargetKind() {
    return targetKind;
  }

  public String getTargetRef() {
    return targetRef;
  }

  public String getTestQuestion() {
    return testQuestion;
  }

  public int getHitCount() {
    return hitCount;
  }

  public String getHitRefs() {
    return hitRefs;
  }

  public String getPermissionSnapshot() {
    return permissionSnapshot;
  }

  public String getJustification() {
    return justification;
  }
}
