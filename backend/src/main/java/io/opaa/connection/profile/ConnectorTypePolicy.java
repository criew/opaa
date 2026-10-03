package io.opaa.connection.profile;

import io.opaa.knowledge.SourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * What the system administration decided for one connector type (ADR-0041, Entscheidung 1). A type
 * without a row is not locked.
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
}
