package io.opaa.asset;

import io.opaa.api.types.AssetVisibility;
import io.opaa.api.types.ExternalAccessState;
import io.opaa.permission.AssetType;
import io.opaa.permission.AssetTypeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A half-open interval {@code [validFrom, validTo)} recording one reach state of an asset - its
 * {@link AssetVisibility}, {@code listed} and its release for Fremdzugaenge (#238, #1731, see
 * docs/features/security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten). It is the
 * third source the readable-asset formula depends on besides direct and group grants. {@code
 * validTo == null} is the current state. Written and closed exclusively by {@link
 * AssetVisibilityHistoryService}; a type without Fremdzugang carries {@code NEVER_SET}.
 */
@Entity
@Table(name = "asset_visibility_history")
public class AssetVisibilityHistory {

  @Id private UUID id;

  @Convert(converter = AssetTypeConverter.class)
  @Column(name = "asset_type", nullable = false, length = 30)
  private AssetType assetType;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "visibility", nullable = false, length = 20)
  private AssetVisibility visibility;

  @Column(name = "listed", nullable = false)
  private boolean listed;

  @Enumerated(EnumType.STRING)
  @Column(name = "external_access_state", nullable = false, length = 20)
  private ExternalAccessState externalAccessState;

  @Column(name = "external_access_expires_at")
  private Instant externalAccessExpiresAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "cause", nullable = false, length = 30)
  private AssetVisibilityHistoryCause cause;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_to")
  private Instant validTo;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AssetVisibilityHistory() {}

  public AssetVisibilityHistory(
      AssetType assetType,
      UUID assetId,
      UUID organizationId,
      AssetVisibility visibility,
      boolean listed,
      ExternalAccessState externalAccessState,
      Instant externalAccessExpiresAt,
      AssetVisibilityHistoryCause cause,
      UUID actorUserId,
      Instant validFrom) {
    this.id = UUID.randomUUID();
    this.assetType = assetType;
    this.assetId = assetId;
    this.organizationId = organizationId;
    this.visibility = visibility;
    this.listed = listed;
    this.externalAccessState = externalAccessState;
    this.externalAccessExpiresAt = externalAccessExpiresAt;
    this.cause = cause;
    this.actorUserId = actorUserId;
    this.validFrom = validFrom;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public void close(Instant validTo) {
    this.validTo = validTo;
  }

  public UUID getId() {
    return id;
  }

  public AssetType getAssetType() {
    return assetType;
  }

  public UUID getAssetId() {
    return assetId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public AssetVisibility getVisibility() {
    return visibility;
  }

  public boolean isListed() {
    return listed;
  }

  public ExternalAccessState getExternalAccessState() {
    return externalAccessState;
  }

  public Instant getExternalAccessExpiresAt() {
    return externalAccessExpiresAt;
  }

  public AssetVisibilityHistoryCause getCause() {
    return cause;
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public Instant getValidFrom() {
    return validFrom;
  }

  public Instant getValidTo() {
    return validTo;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
