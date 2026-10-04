package io.opaa.connection.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * The profile a library is connected through. A library without a row carries its own address; a
 * row without a profile is a library whose profile was deleted ("Zugang entfernt").
 */
@Entity
@Table(name = "library_connections")
public class LibraryConnection {

  @Id
  @Column(name = "library_id")
  private UUID libraryId;

  @Column(name = "profile_id")
  private UUID profileId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  protected LibraryConnection() {}

  /**
   * Whether {@code connection} - {@code null} for a library without a row - reaches its source
   * through a profile. A library for which this is false has its own address, also after its
   * profile was deleted.
   */
  public static boolean throughProfile(LibraryConnection connection) {
    return connection != null && connection.getProfileId() != null;
  }

  public LibraryConnection(UUID libraryId, UUID profileId, Instant now) {
    this.libraryId = libraryId;
    this.profileId = profileId;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void moveTo(UUID profileId, Instant now) {
    this.profileId = profileId;
    this.updatedAt = now;
  }

  public UUID getLibraryId() {
    return libraryId;
  }

  /** {@code null} once the profile is deleted. */
  public UUID getProfileId() {
    return profileId;
  }
}
