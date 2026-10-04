package io.opaa.connection.log;

import io.opaa.api.types.ConnectionEndCause;
import io.opaa.api.types.ConnectionLogEventType;
import io.opaa.api.types.ConnectionLogOwnerKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * One entry of the connection log: who acted, whose connection (person, library or profile), which
 * profile, which event, when, and why it ended. Immutable like {@code AuditLogEntry}: no setter,
 * always new, and the database leaves the application only {@code INSERT} and {@code SELECT}.
 * Created only by {@link ConnectionLog}.
 */
@Entity
@Table(name = "connection_log")
public class ConnectionLogEntry implements Persistable<UUID> {

  @Id
  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 30)
  private ConnectionLogEventType eventType;

  @Column(name = "actor_ref", nullable = false)
  private String actorRef;

  @Enumerated(EnumType.STRING)
  @Column(name = "owner_kind", nullable = false, length = 10)
  private ConnectionLogOwnerKind ownerKind;

  @Column(name = "person_ref")
  private String personRef;

  @Column(name = "library_id")
  private UUID libraryId;

  @Column(name = "account_label", length = 500)
  private String accountLabel;

  @Column(name = "profile_id", nullable = false)
  private UUID profileId;

  @Column(name = "profile_name", nullable = false)
  private String profileName;

  @Enumerated(EnumType.STRING)
  @Column(name = "cause", length = 30)
  private ConnectionEndCause cause;

  protected ConnectionLogEntry() {}

  ConnectionLogEntry(
      UUID organizationId,
      Instant recordedAt,
      ConnectionLogEventType eventType,
      String actorRef,
      ConnectionLogOwnerKind ownerKind,
      String personRef,
      UUID libraryId,
      String accountLabel,
      UUID profileId,
      String profileName,
      ConnectionEndCause cause) {
    this.eventId = UUID.randomUUID();
    this.organizationId = organizationId;
    this.recordedAt = recordedAt;
    this.eventType = eventType;
    this.actorRef = actorRef;
    this.ownerKind = ownerKind;
    this.personRef = personRef;
    this.libraryId = libraryId;
    this.accountLabel = accountLabel;
    this.profileId = profileId;
    this.profileName = profileName;
    this.cause = cause;
  }

  @Override
  public UUID getId() {
    return eventId;
  }

  @Override
  public boolean isNew() {
    return true;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public Instant getRecordedAt() {
    return recordedAt;
  }

  public ConnectionLogEventType getEventType() {
    return eventType;
  }

  public String getActorRef() {
    return actorRef;
  }

  public ConnectionLogOwnerKind getOwnerKind() {
    return ownerKind;
  }

  public String getPersonRef() {
    return personRef;
  }

  public UUID getLibraryId() {
    return libraryId;
  }

  public String getAccountLabel() {
    return accountLabel;
  }

  public UUID getProfileId() {
    return profileId;
  }

  public String getProfileName() {
    return profileName;
  }

  public ConnectionEndCause getCause() {
    return cause;
  }
}
