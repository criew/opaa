package io.opaa.library;

import io.opaa.api.types.LibraryVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A half-open interval {@code [validFrom, validTo)} recording one {@link LibraryVisibility}/{@code
 * listed} state a {@link KnowledgeLibrary} was in (#238, see
 * docs/features/spaces-and-assets.md#nachweisbarkeit-historisierung-von-rechten) - the third source
 * the readable-library formula depends on besides direct and group grants ({@link
 * AssetGrantHistory}). {@code validTo == null} means the interval is still open, i.e. this is the
 * library's current state. Written and closed exclusively by {@link PermissionHistoryService}.
 */
@Entity
@Table(name = "library_visibility_history")
public class LibraryVisibilityHistory {

  @Id private UUID id;

  @Column(name = "library_id", nullable = false)
  private UUID libraryId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "visibility", nullable = false, length = 20)
  private LibraryVisibility visibility;

  @Column(name = "listed", nullable = false)
  private boolean listed;

  @Enumerated(EnumType.STRING)
  @Column(name = "cause", nullable = false, length = 30)
  private LibraryVisibilityHistoryCause cause;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_to")
  private Instant validTo;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected LibraryVisibilityHistory() {}

  public LibraryVisibilityHistory(
      UUID libraryId,
      UUID organizationId,
      LibraryVisibility visibility,
      boolean listed,
      LibraryVisibilityHistoryCause cause,
      UUID actorUserId,
      Instant validFrom) {
    this.id = UUID.randomUUID();
    this.libraryId = libraryId;
    this.organizationId = organizationId;
    this.visibility = visibility;
    this.listed = listed;
    this.cause = cause;
    this.actorUserId = actorUserId;
    this.validFrom = validFrom;
  }

  /**
   * A zero-length marker interval ({@code validFrom == validTo == at}) recording that {@code
   * library}'s visibility interval ended with {@code cause} - see {@code
   * io.opaa.library.AssetGrantHistory#terminal} for why a closing-only change needs its own marker
   * row rather than relying on the closed interval alone: the closed interval's own cause must stay
   * whatever it originally was ({@code CREATED} or {@code VISIBILITY_CHANGED}), and the closing
   * event is a separate, actor-bearing fact #238's acceptance criteria require to be recorded.
   */
  static LibraryVisibilityHistory terminal(
      KnowledgeLibrary library, LibraryVisibilityHistoryCause cause, UUID actorUserId, Instant at) {
    LibraryVisibilityHistory marker =
        new LibraryVisibilityHistory(
            library.getId(),
            library.getOrganizationId(),
            library.getVisibility(),
            library.isListed(),
            cause,
            actorUserId,
            at);
    marker.close(at);
    return marker;
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

  public UUID getLibraryId() {
    return libraryId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public LibraryVisibility getVisibility() {
    return visibility;
  }

  public boolean isListed() {
    return listed;
  }

  public LibraryVisibilityHistoryCause getCause() {
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
