package io.opaa.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An open request of the system administration for the AUDITOR role of one account. The role takes
 * effect only once an auditor confirms; a decided request is deleted and lives on in the audit log
 * only. At most one per account ({@code ux_auditor_role_requests_user}).
 */
@Entity
@Table(name = "auditor_role_requests")
public class AuditorRoleRequest {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "requested_by_user_id", nullable = false)
  private UUID requestedByUserId;

  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt;

  protected AuditorRoleRequest() {}

  public AuditorRoleRequest(
      UUID organizationId, UUID userId, UUID requestedByUserId, Instant requestedAt) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.userId = userId;
    this.requestedByUserId = requestedByUserId;
    this.requestedAt = requestedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getRequestedByUserId() {
    return requestedByUserId;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }
}
