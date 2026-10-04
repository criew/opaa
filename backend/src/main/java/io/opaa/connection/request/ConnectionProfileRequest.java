package io.opaa.connection.request;

import io.opaa.knowledge.SourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A person's request for a connection profile ("Zugangswunsch"): source type and server address,
 * with an optional reason. It goes with the requesting account; once resolved it keeps who resolved
 * it, when, the profile serving it (cleared when that profile is deleted) and the answer.
 */
@Entity
@Table(name = "connection_profile_requests")
public class ConnectionProfileRequest {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "source_type", nullable = false, length = SourceType.MAX_LENGTH)
  private SourceType sourceType;

  @Column(name = "server_url", nullable = false, length = 2000)
  private String serverUrl;

  @Column(name = "reason", length = 500)
  private String reason;

  @Column(name = "requested_by", nullable = false)
  private UUID requestedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private ProfileRequestState state;

  @Column(name = "resolved_by")
  private UUID resolvedBy;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "profile_id")
  private UUID profileId;

  @Column(name = "answer", length = 500)
  private String answer;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  protected ConnectionProfileRequest() {}

  ConnectionProfileRequest(
      UUID organizationId,
      SourceType sourceType,
      String serverUrl,
      String reason,
      UUID requestedBy,
      Instant now) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.sourceType = sourceType;
    this.serverUrl = serverUrl;
    this.reason = reason;
    this.requestedBy = requestedBy;
    this.createdAt = now;
    this.state = ProfileRequestState.OPEN;
  }

  void resolve(
      ProfileRequestState outcome, UUID resolvedBy, Instant now, UUID profileId, String answer) {
    this.state = outcome;
    this.resolvedBy = resolvedBy;
    this.resolvedAt = now;
    this.profileId = profileId;
    this.answer = answer;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public SourceType getSourceType() {
    return sourceType;
  }

  public String getServerUrl() {
    return serverUrl;
  }

  public String getReason() {
    return reason;
  }

  public UUID getRequestedBy() {
    return requestedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public ProfileRequestState getState() {
    return state;
  }

  public UUID getResolvedBy() {
    return resolvedBy;
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }

  public UUID getProfileId() {
    return profileId;
  }

  public String getAnswer() {
    return answer;
  }
}
