package io.opaa.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every method is scoped to one person: a mark is only ever read or written together with the
 * {@code userId} it belongs to, so no caller can reach another person's marks.
 */
public interface ChatPersonalMarkRepository
    extends JpaRepository<ChatPersonalMark, ChatPersonalMark.Key> {

  /** The person's marks on their own chats of one space - one query for the whole list. */
  @Query(
      "select m from ChatPersonalMark m, Chat c where c.id = m.chatId and c.spaceId = :spaceId"
          + " and c.authorId = :userId and m.userId = :userId")
  List<ChatPersonalMark> findOwnMarksInSpace(
      @Param("spaceId") UUID spaceId, @Param("userId") UUID userId);

  @Query(
      "select m.pinnedAt from ChatPersonalMark m where m.chatId = :chatId and m.userId = :userId")
  Optional<Instant> findPinnedAt(@Param("chatId") UUID chatId, @Param("userId") UUID userId);

  @Query(
      "select m.archivedAt from ChatPersonalMark m where m.chatId = :chatId and m.userId = :userId")
  Optional<Instant> findArchivedAt(@Param("chatId") UUID chatId, @Param("userId") UUID userId);

  /**
   * The person's archived chats of one space, most recently archived first. Only their own chats:
   * the author condition keeps a mark on a chat they can no longer see out of the list.
   */
  @Query(
      value =
          "select new io.opaa.chat.ChatListEntry(c, m.pinnedAt, m.archivedAt)"
              + " from ChatPersonalMark m, Chat c where c.id = m.chatId and c.spaceId = :spaceId"
              + " and c.authorId = :userId and m.userId = :userId and m.archivedAt is not null"
              + " order by m.archivedAt desc, c.id desc",
      countQuery =
          "select count(m) from ChatPersonalMark m, Chat c where c.id = m.chatId"
              + " and c.spaceId = :spaceId and c.authorId = :userId and m.userId = :userId"
              + " and m.archivedAt is not null")
  Page<ChatListEntry> findArchivedInSpace(
      @Param("spaceId") UUID spaceId, @Param("userId") UUID userId, Pageable pageable);

  /**
   * Idempotent: an already pinned chat keeps the time it was first pinned. Pinning takes the chat
   * out of the person's archive - a chat is never pinned and archived at once.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO chat_personal_marks (chat_id, user_id, pinned_at)"
              + " VALUES (:chatId, :userId, :pinnedAt)"
              + " ON CONFLICT (chat_id, user_id) DO UPDATE"
              + " SET pinned_at = COALESCE(chat_personal_marks.pinned_at, EXCLUDED.pinned_at),"
              + " archived_at = NULL",
      nativeQuery = true)
  void pin(
      @Param("chatId") UUID chatId,
      @Param("userId") UUID userId,
      @Param("pinnedAt") Instant pinnedAt);

  @Modifying
  @Query(
      value =
          "UPDATE chat_personal_marks SET pinned_at = NULL"
              + " WHERE chat_id = :chatId AND user_id = :userId",
      nativeQuery = true)
  void clearPin(@Param("chatId") UUID chatId, @Param("userId") UUID userId);

  /**
   * Idempotent: an already archived chat keeps the time it was first archived. Archiving unpins the
   * chat.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO chat_personal_marks (chat_id, user_id, archived_at)"
              + " VALUES (:chatId, :userId, :archivedAt)"
              + " ON CONFLICT (chat_id, user_id) DO UPDATE"
              + " SET archived_at = COALESCE(chat_personal_marks.archived_at, EXCLUDED.archived_at),"
              + " pinned_at = NULL",
      nativeQuery = true)
  void archive(
      @Param("chatId") UUID chatId,
      @Param("userId") UUID userId,
      @Param("archivedAt") Instant archivedAt);

  /**
   * One targeted statement, so it needs no previously loaded row; callers follow it with {@link
   * #deleteIfUnmarked}.
   *
   * @return the number of rows that were archived (0 or 1)
   */
  @Modifying
  @Query(
      value =
          "UPDATE chat_personal_marks SET archived_at = NULL"
              + " WHERE chat_id = :chatId AND user_id = :userId AND archived_at IS NOT NULL",
      nativeQuery = true)
  int clearArchive(@Param("chatId") UUID chatId, @Param("userId") UUID userId);

  /**
   * Removes the row once it carries no mark at all, so no empty row stays behind as a trace that
   * the person once marked this chat. Every mark column belongs in this predicate.
   */
  @Modifying
  @Query(
      value =
          "DELETE FROM chat_personal_marks"
              + " WHERE chat_id = :chatId AND user_id = :userId"
              + " AND pinned_at IS NULL AND archived_at IS NULL",
      nativeQuery = true)
  void deleteIfUnmarked(@Param("chatId") UUID chatId, @Param("userId") UUID userId);
}
