package io.opaa.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

  /** Idempotent: an already pinned chat keeps the time it was first pinned. */
  @Modifying
  @Query(
      value =
          "INSERT INTO chat_personal_marks (chat_id, user_id, pinned_at)"
              + " VALUES (:chatId, :userId, :pinnedAt)"
              + " ON CONFLICT (chat_id, user_id) DO UPDATE"
              + " SET pinned_at = COALESCE(chat_personal_marks.pinned_at, EXCLUDED.pinned_at)",
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
   * Removes the row once it carries no mark at all, so no empty row stays behind as a trace that
   * the person once marked this chat. Every mark column belongs in this predicate.
   */
  @Modifying
  @Query(
      value =
          "DELETE FROM chat_personal_marks"
              + " WHERE chat_id = :chatId AND user_id = :userId AND pinned_at IS NULL",
      nativeQuery = true)
  void deleteIfUnmarked(@Param("chatId") UUID chatId, @Param("userId") UUID userId);
}
