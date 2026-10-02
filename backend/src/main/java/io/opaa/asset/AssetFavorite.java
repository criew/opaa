package io.opaa.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One person's favorite mark on one asset (ADR-0039, Entscheidung 7). It belongs to the person,
 * never to the asset: it confers no right, has no history, and nobody else can read it. Written
 * only through {@link AssetFavoriteRepository}'s statements.
 */
@Entity
@Table(name = "asset_favorites")
@IdClass(AssetFavorite.Key.class)
public class AssetFavorite {

  @Id
  @Column(name = "asset_id", nullable = false, updatable = false)
  private UUID assetId;

  @Id
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AssetFavorite() {}

  public UUID getAssetId() {
    return assetId;
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

  /** Composite primary key: one row per asset and person. */
  public static class Key implements Serializable {
    private UUID assetId;
    private UUID userId;

    protected Key() {}

    public Key(UUID assetId, UUID userId) {
      this.assetId = assetId;
      this.userId = userId;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(assetId, key.assetId)
          && Objects.equals(userId, key.userId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(assetId, userId);
    }
  }
}
