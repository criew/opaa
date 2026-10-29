package io.opaa.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A request for the AUDITOR role of one account, asked by a system administrator or by the roles
 * claim of an identity provider - exactly one of the two. The role takes effect only once an
 * auditor confirms. A decided request of the administration is deleted and lives on in the audit
 * log only; a declined request of a provider keeps {@code declinedAt}, so the same claim does not
 * reopen it at the next sign-in. At most one per account ({@code ux_auditor_role_requests_user}).
 * Written through {@link AuditorRoleRequestRepository#insertIfAbsent} only.
 */
@Entity
@Table(name = "auditor_role_requests")
public class AuditorRoleRequest {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "requested_by_user_id")
  private UUID requestedByUserId;

  @Column(name = "requested_by_provider_id")
  private UUID requestedByProviderId;

  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt;

  @Column(name = "declined_at")
  private Instant declinedAt;

  protected AuditorRoleRequest() {}

  /** An unsaved instance for tests and read models; persisted rows come from the repository. */
  public AuditorRoleRequest(
      UUID organizationId,
      UUID userId,
      UUID requestedByUserId,
      UUID requestedByProviderId,
      Instant requestedAt) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.userId = userId;
    this.requestedByUserId = requestedByUserId;
    this.requestedByProviderId = requestedByProviderId;
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

  /** The requesting administrator; {@code null} for a request of a provider. */
  public UUID getRequestedByUserId() {
    return requestedByUserId;
  }

  /** The provider whose roles claim asked; {@code null} for a request of the administration. */
  public UUID getRequestedByProviderId() {
    return requestedByProviderId;
  }

  public boolean isFromProvider() {
    return requestedByProviderId != null;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }

  public Instant getDeclinedAt() {
    return declinedAt;
  }
}
