package io.opaa.chat;

import io.opaa.api.types.ChatAttachmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A document attached to a chat question (docs/handbuch/spaces/suche.md, "Dokumente an eine Frage
 * anhängen"). It belongs to its author alone; {@code chatId} is null until a question names it and
 * then never changes, so the attachment is deleted with its chat ({@code ON DELETE CASCADE}). Its
 * text lives in {@code chat_attachment_chunks}, its original in the upload storage under {@link
 * io.opaa.knowledge.UploadedOriginalStore#CHAT_ATTACHMENT_AREA}.
 */
@Entity
@Table(name = "chat_attachments")
public class ChatAttachment {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "space_id", nullable = false, updatable = false)
  private UUID spaceId;

  @Column(name = "author_id", nullable = false, updatable = false)
  private UUID authorId;

  @Column(name = "chat_id")
  private UUID chatId;

  @Column(name = "message_id")
  private UUID messageId;

  @Column(name = "file_name", nullable = false, updatable = false)
  private String fileName;

  @Column(name = "content_type")
  private String contentType;

  @Column(name = "size_bytes", nullable = false, updatable = false)
  private long sizeBytes;

  @Column(name = "original_locator")
  private String originalLocator;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ChatAttachmentStatus status;

  @Column(name = "failure_reason", length = 500)
  private String failureReason;

  @Column(name = "text_chars")
  private Integer textChars;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "processed_at")
  private Instant processedAt;

  protected ChatAttachment() {}

  ChatAttachment(
      UUID organizationId,
      UUID spaceId,
      UUID authorId,
      String fileName,
      String contentType,
      long sizeBytes,
      Instant createdAt) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.spaceId = spaceId;
    this.authorId = authorId;
    this.fileName = fileName;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.status = ChatAttachmentStatus.PROCESSING;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getSpaceId() {
    return spaceId;
  }

  public UUID getAuthorId() {
    return authorId;
  }

  public UUID getChatId() {
    return chatId;
  }

  public UUID getMessageId() {
    return messageId;
  }

  public String getFileName() {
    return fileName;
  }

  public String getContentType() {
    return contentType;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public String getOriginalLocator() {
    return originalLocator;
  }

  void setOriginalLocator(String originalLocator) {
    this.originalLocator = originalLocator;
  }

  public ChatAttachmentStatus getStatus() {
    return status;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public Integer getTextChars() {
    return textChars;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }

  public boolean isReady() {
    return status == ChatAttachmentStatus.READY;
  }
}
