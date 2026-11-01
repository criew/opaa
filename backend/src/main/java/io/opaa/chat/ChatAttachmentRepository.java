package io.opaa.chat;

import io.opaa.api.types.ChatAttachmentStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every lookup goes through the author: an attachment of another person is never reachable by its
 * id alone, not even for an administrator.
 */
public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, UUID> {

  Optional<ChatAttachment> findByIdAndAuthorId(UUID id, UUID authorId);

  List<ChatAttachment> findByAuthorIdOrderByCreatedAtDesc(UUID authorId);

  List<ChatAttachment> findByIdInAndAuthorId(Collection<UUID> ids, UUID authorId);

  /** The attachments of a chat in the order they were uploaded. */
  List<ChatAttachment> findByChatIdOrderByCreatedAtAsc(UUID chatId);

  long countByChatId(UUID chatId);

  /** The locators of an organization's attachments that still name an original. */
  @Query(
      "select a.originalLocator from ChatAttachment a where a.organizationId = :organizationId"
          + " and a.originalLocator is not null")
  List<String> findOriginalLocators(@Param("organizationId") UUID organizationId);

  @Query("select distinct a.organizationId from ChatAttachment a")
  List<UUID> findOrganizationIds();

  /** Attachments no question ever named, uploaded before {@code before}. */
  @Query("select a from ChatAttachment a where a.chatId is null and a.createdAt < :before")
  List<ChatAttachment> findUnboundBefore(@Param("before") Instant before);

  /**
   * Deletes {@code id} only while no question has named it and it is older than {@code before}; a
   * binding committed meanwhile keeps it.
   */
  @Modifying
  @Transactional
  @Query(
      "delete from ChatAttachment a where a.id = :id and a.chatId is null and a.createdAt < :before")
  int deleteIfUnboundBefore(@Param("id") UUID id, @Param("before") Instant before);

  /** Attachments still being read since before {@code before}: their reading was lost. */
  @Query("select a.id from ChatAttachment a where a.status = :status and a.createdAt < :before")
  List<UUID> findIdsByStatusBefore(
      @Param("status") ChatAttachmentStatus status, @Param("before") Instant before);

  /** Ends a reading; conditional, so a concurrently deleted or finished row is left alone. */
  @Modifying
  @Transactional
  @Query(
      "update ChatAttachment a set a.status = io.opaa.api.types.ChatAttachmentStatus.READY,"
          + " a.textChars = :textChars, a.processedAt = :at"
          + " where a.id = :id and a.status = io.opaa.api.types.ChatAttachmentStatus.PROCESSING")
  int markReady(@Param("id") UUID id, @Param("textChars") int textChars, @Param("at") Instant at);

  @Modifying
  @Transactional
  @Query(
      "update ChatAttachment a set a.status = io.opaa.api.types.ChatAttachmentStatus.FAILED,"
          + " a.failureReason = :reason, a.processedAt = :at"
          + " where a.id = :id and a.status = io.opaa.api.types.ChatAttachmentStatus.PROCESSING")
  int markFailed(@Param("id") UUID id, @Param("reason") String reason, @Param("at") Instant at);

  /**
   * Binds the named attachments of {@code authorId} to the question {@code messageId} of {@code
   * chatId}. Only an attachment still unbound or already in that chat moves; the caller compares
   * the count.
   */
  @Modifying
  @Query(
      "update ChatAttachment a set a.chatId = :chatId, a.messageId = :messageId"
          + " where a.id in :ids and a.authorId = :authorId"
          + " and (a.chatId is null or a.chatId = :chatId) and a.messageId is null")
  int bindToMessage(
      @Param("ids") Collection<UUID> ids,
      @Param("authorId") UUID authorId,
      @Param("chatId") UUID chatId,
      @Param("messageId") UUID messageId);
}
