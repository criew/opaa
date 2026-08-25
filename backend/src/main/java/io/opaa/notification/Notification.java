package io.opaa.notification;

import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.NotificationType;
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
 * A minimal, persisted in-app notification (#203) - just enough to satisfy "der Eigentümer wird
 * aktiv benachrichtigt" (docs/features/spaces-and-assets.md#assets-in-einen-space-assoziieren)
 * without building the in-app postbox (notifications + todos) the maintainer has not specified yet.
 * Deliberately narrow, but shaped so a future postbox can grow from this table rather than replace
 * it: recipient/type/objectType/objectId/title/body/readAt/createdAt is the same shape a
 * todo-capable postbox entry would need.
 */
@Entity
@Table(name = "notifications")
public class Notification {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "recipient_user_id", nullable = false)
  private UUID recipientUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 50)
  private NotificationType type;

  @Enumerated(EnumType.STRING)
  @Column(name = "object_type", length = 30)
  private AuditObjectType objectType;

  @Column(name = "object_id")
  private UUID objectId;

  @Column(name = "title", nullable = false, length = 255)
  private String title;

  @Column(name = "body", length = 2000)
  private String body;

  @Column(name = "read_at")
  private Instant readAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Notification() {}

  public Notification(
      UUID organizationId,
      UUID recipientUserId,
      NotificationType type,
      AuditObjectType objectType,
      UUID objectId,
      String title,
      String body) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.recipientUserId = recipientUserId;
    this.type = type;
    this.objectType = objectType;
    this.objectId = objectId;
    this.title = title;
    this.body = body;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public void markRead() {
    if (this.readAt == null) {
      this.readAt = Instant.now();
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getRecipientUserId() {
    return recipientUserId;
  }

  public NotificationType getType() {
    return type;
  }

  public AuditObjectType getObjectType() {
    return objectType;
  }

  public UUID getObjectId() {
    return objectId;
  }

  public String getTitle() {
    return title;
  }

  public String getBody() {
    return body;
  }

  public Instant getReadAt() {
    return readAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
