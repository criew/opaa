package io.opaa.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatNoteItemRepository extends JpaRepository<ChatNoteItem, UUID> {

  /** The chat's note, oldest point first - the order the cap drops from and the API delivers. */
  List<ChatNoteItem> findByChatIdOrderByPositionAsc(UUID chatId);

  /**
   * A point looked up through its chat, never by id alone: the chat is what carries the
   * author/organization boundary, so a point of a foreign chat must not be reachable with a guessed
   * id.
   */
  Optional<ChatNoteItem> findByIdAndChatId(UUID id, UUID chatId);

  /**
   * The next free position, {@code 0} for an empty note. Not a row count: positions are never
   * reused, so a removed point must not make the next one collide with an existing row (the same
   * reasoning {@code ChatMessageWriter#nextSequenceFor} follows for {@code chat_messages}).
   */
  @Query("select coalesce(max(i.position), -1) + 1 from ChatNoteItem i where i.chatId = :chatId")
  int nextPositionFor(@Param("chatId") UUID chatId);
}
