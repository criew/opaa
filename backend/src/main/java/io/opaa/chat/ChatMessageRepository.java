package io.opaa.chat;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

  /**
   * Ordered by the application-assigned {@code sequence}, not {@code created_at} - see {@link
   * ChatMessage}'s Javadoc for why timestamp ordering alone is not reliable for two messages of the
   * same turn.
   */
  List<ChatMessage> findByChatIdOrderBySequenceAsc(UUID chatId);

  /**
   * The highest existing {@code sequence} for {@code chatId}, or {@code null} for a chat with no
   * messages yet - {@code ChatMessageWriter#nextSequenceFor} adds 1 to this. Never undercounts
   * after a gap, unlike a row count over a table a deletion can leave gaps in (#889).
   */
  @Query("select max(m.sequence) from ChatMessage m where m.chatId = :chatId")
  Integer findMaxSequenceByChatId(@Param("chatId") UUID chatId);
}
