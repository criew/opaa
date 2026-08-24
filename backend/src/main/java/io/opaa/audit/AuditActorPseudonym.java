package io.opaa.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The separately held mapping from a person's pseudonym id to their real {@code user_id}. {@code
 * audit_log} carries only the pseudonym (as {@code actor_ref}/{@code subject_ref}); this is the
 * only place that connects it back to a person, and it is the row deleted - independently of and
 * without touching any {@link AuditLogEntry} - when the account is deleted, via an {@code ON DELETE
 * CASCADE} foreign key so the removal happens at the database level.
 *
 * <p>Rows are only ever created via {@link AuditActorPseudonymRepository#insertIfAbsent}'s native
 * {@code INSERT ... ON CONFLICT} query, never through this entity's constructor - so there is
 * deliberately no public constructor beyond the protected no-arg one JPA needs to hydrate rows it
 * reads back.
 */
@Entity
@Table(name = "audit_actor_pseudonyms")
public class AuditActorPseudonym {

  @Id
  @Column(name = "pseudonym_id")
  private UUID pseudonymId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AuditActorPseudonym() {}

  public UUID getPseudonymId() {
    return pseudonymId;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
