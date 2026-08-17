package io.opaa.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The separately held mapping from a person's pseudonym id to their real {@code user_id}
 * (docs/features/security-and-compliance.md#unveränderlichkeit-und-löschrecht). {@code audit_log}
 * carries only the pseudonym (as {@code actor_ref}/{@code subject_ref}); this is the only place
 * that connects it back to a person, and it is the row deleted - independently of and without
 * touching any {@link AuditLogEntry} - when the account is deleted. {@code
 * fk_audit_actor_pseudonyms_user} (migration 017) is {@code ON DELETE CASCADE}, so that removal
 * happens automatically at the database level rather than depending on application code remembering
 * to do it.
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

  public AuditActorPseudonym(UUID userId, UUID organizationId) {
    this.pseudonymId = UUID.randomUUID();
    this.userId = userId;
    this.organizationId = organizationId;
    this.createdAt = Instant.now();
  }

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
