package io.opaa.connection.profile;

import io.opaa.knowledge.SourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * What the system administration decided for one connector type (ADR-0041, Entscheidung 1): its
 * lock and its profile requirement. A type without a row is neither locked nor switched to profiles
 * only.
 */
@Entity
@Table(name = "connector_type_policies")
public class ConnectorTypePolicy {

  /** The type key; a plain column, because an attribute converter does not apply to an id. */
  @Id
  @Column(name = "source_type", length = SourceType.MAX_LENGTH)
  private String sourceType;

  @Column(name = "locked_at")
  private Instant lockedAt;

  @Column(name = "profile_required_at")
  private Instant profileRequiredAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "own_address_stock", length = 16)
  private OwnAddressStock ownAddressStock;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ConnectorTypePolicy() {}

  public ConnectorTypePolicy(SourceType sourceType, Instant now) {
    this.sourceType = sourceType.key();
    this.updatedAt = now;
  }

  public SourceType getSourceType() {
    return SourceType.of(sourceType);
  }

  /** Since when the type is locked, {@code null} while it is not. */
  public Instant getLockedAt() {
    return lockedAt;
  }

  public boolean isLocked() {
    return lockedAt != null;
  }

  void lockedSince(Instant at, Instant now) {
    this.lockedAt = at;
    this.updatedAt = now;
  }

  /** Since when the type is usable only through a profile, {@code null} while it is not. */
  public Instant getProfileRequiredAt() {
    return profileRequiredAt;
  }

  public boolean isProfileRequired() {
    return profileRequiredAt != null;
  }

  /** What happens to the libraries with their own address, {@code null} without requirement. */
  public OwnAddressStock getOwnAddressStock() {
    return ownAddressStock;
  }

  /** Switches the requirement on with {@code stock} and keeps its start, or off with null. */
  void requireProfiles(OwnAddressStock stock, Instant now) {
    this.profileRequiredAt =
        stock == null ? null : Objects.requireNonNullElse(profileRequiredAt, now);
    this.ownAddressStock = stock;
    this.updatedAt = now;
  }
}
