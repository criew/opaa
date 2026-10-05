package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionEndCause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The profile a library is connected through. A library without a row carries its own address; a
 * row without a profile is a library whose profile was deleted ("Zugang entfernt"). On a profile
 * signing in by OAuth the row also describes the library's own consent ("Quelle verbinden"): the
 * account at the provider, who consented, who is responsible and why the consent ended.
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

  @Column(name = "account_label", length = 500)
  private String accountLabel;

  @Column(name = "connected_by")
  private UUID connectedBy;

  @Column(name = "connected_at")
  private Instant connectedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "responsible_type", length = 10)
  private ResponsibleType responsibleType;

  @Column(name = "responsible_id")
  private UUID responsibleId;

  @Enumerated(EnumType.STRING)
  @Column(name = "ended_cause", length = 30)
  private ConnectionEndCause endedCause;

  @Column(name = "ended_at")
  private Instant endedAt;

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

  /**
   * The library's consent was given now by {@code by} as {@code accountLabel} ({@code null} where
   * the connector names none), {@code responsible} for it; a previous end is lifted.
   */
  public void consented(String accountLabel, UUID by, Responsible responsible, Instant now) {
    this.accountLabel = accountLabel;
    this.connectedBy = by;
    this.connectedAt = now;
    this.responsibleType = responsible.type();
    this.responsibleId = responsible.id();
    this.endedCause = null;
    this.endedAt = null;
    this.updatedAt = now;
  }

  /** The library's consent ended now for {@code cause}; the first end stands. */
  public void consentEnded(ConnectionEndCause cause, Instant now) {
    if (endedCause == null) {
      this.endedCause = cause;
      this.endedAt = now;
      this.updatedAt = now;
    }
  }

  /** Forgets the consent, as when the library moves to another profile or its own address. */
  public void consentForgotten(Instant now) {
    this.accountLabel = null;
    this.connectedBy = null;
    this.connectedAt = null;
    this.responsibleType = null;
    this.responsibleId = null;
    this.endedCause = null;
    this.endedAt = null;
    this.updatedAt = now;
  }

  public UUID getLibraryId() {
    return libraryId;
  }

  /** {@code null} once the profile is deleted. */
  public UUID getProfileId() {
    return profileId;
  }

  /** The account at the provider the library's consent was given as, {@code null} for unknown. */
  public String getAccountLabel() {
    return accountLabel;
  }

  /** Who gave the library's consent, {@code null} for none or a deleted account. */
  public UUID getConnectedBy() {
    return connectedBy;
  }

  /** When the library's consent was last given, {@code null} for none. */
  public Instant getConnectedAt() {
    return connectedAt;
  }

  /** Who is responsible for the library's consent, empty for none. */
  public Optional<Responsible> getResponsible() {
    return responsibleType == null
        ? Optional.empty()
        : Optional.of(new Responsible(responsibleType, responsibleId));
  }

  /** Why the library's consent ended, {@code null} while it stands or for none. */
  public ConnectionEndCause getEndedCause() {
    return endedCause;
  }

  public Instant getEndedAt() {
    return endedAt;
  }

  /** Whether a person or a group answers for a library's consent. */
  public enum ResponsibleType {
    USER,
    GROUP
  }

  /** The person or group answering for a library's consent: warned of its end, reconnects it. */
  public record Responsible(ResponsibleType type, UUID id) {

    public Responsible {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(id, "id");
    }

    public static Responsible user(UUID userId) {
      return new Responsible(ResponsibleType.USER, userId);
    }

    public static Responsible group(UUID groupId) {
      return new Responsible(ResponsibleType.GROUP, groupId);
    }
  }
}
