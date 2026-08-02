package io.opaa.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The hard tenant boundary. Nothing crosses it, not even system administration.
 *
 * <p>Exactly one organization is seeded for this stage of the product; multi-organization
 * management is out of scope. {@link #DEFAULT_ID} matches the row inserted by the Liquibase seed
 * changelog and is used to assign every user and space to that single organization.
 */
@Entity
@Table(name = "organizations")
public class Organization {

  /**
   * Fixed identifier of the single seeded organization. Kept as a well-known constant until
   * multi-organization management exists.
   */
  public static final UUID DEFAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Id private UUID id;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Organization() {}

  public Organization(UUID id, String name) {
    this.id = id;
    this.name = name;
  }

  @PrePersist
  void onCreate() {
    if (this.createdAt == null) {
      this.createdAt = Instant.now();
    }
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
