package io.opaa.externalaccess.token;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The identity of one Fachverfahren on the external access channel (ADR-0035, Entscheidung 7). Not
 * a person: no sign-in, no role, no membership, and no row in {@code users} - which is what keeps
 * it out of every person list, selection field, directory sync, inactivity rule and
 * self-disclosure.
 *
 * <p>The responsible person is a contact for renewals and nothing else. Her account may be locked
 * or deleted without the service account or its tokens noticing; deactivation is the one act that
 * ends the tokens, and it is final.
 */
@Entity
@Table(name = "service_accounts")
public class ServiceAccount {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "description", length = 1000)
  private String description;

  @Column(name = "responsible_user_id")
  private UUID responsibleUserId;

  @Column(name = "rate_limit_per_hour")
  private Integer rateLimitPerHour;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "deactivated_at")
  private Instant deactivatedAt;

  @Version private long version;

  protected ServiceAccount() {}

  public ServiceAccount(
      UUID organizationId,
      String name,
      String description,
      UUID responsibleUserId,
      Integer rateLimitPerHour,
      Instant createdAt) {
    this.id = UUID.randomUUID();
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    update(name, description, responsibleUserId, rateLimitPerHour);
  }

  /** Replaces the four editable facts at once; the caller has validated them. */
  public void update(
      String name, String description, UUID responsibleUserId, Integer rateLimitPerHour) {
    this.name = Objects.requireNonNull(name, "name");
    this.description = description;
    this.responsibleUserId = Objects.requireNonNull(responsibleUserId, "responsibleUserId");
    this.rateLimitPerHour = rateLimitPerHour;
  }

  public boolean isActive() {
    return deactivatedAt == null;
  }

  /** Final: there is no way back, a Fachverfahren that returns gets a new account. */
  public void deactivate(Instant now) {
    if (deactivatedAt == null) {
      this.deactivatedAt = Objects.requireNonNull(now, "now");
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  /** {@code null} once the responsible account was deleted. */
  public UUID getResponsibleUserId() {
    return responsibleUserId;
  }

  /** The account's own hourly quota, or {@code null} while the channel setting applies. */
  public Integer getRateLimitPerHour() {
    return rateLimitPerHour;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getDeactivatedAt() {
    return deactivatedAt;
  }

  @Override
  public boolean equals(Object o) {
    return this == o || (o instanceof ServiceAccount other && id.equals(other.id));
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
