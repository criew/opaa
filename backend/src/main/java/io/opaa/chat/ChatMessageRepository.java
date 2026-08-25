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
   * #889: the highest existing {@code sequence} for {@code chatId}, or {@code null} for a chat with
   * no messages yet - {@link ChatService#nextSequenceFor} adds 1 to this, replacing a plain {@code
   * COUNT(*)} (see that method's Javadoc for why counting rows breaks permanently once any
   * non-trailing message of a chat is gone: the count then undercounts the next free sequence and
   * collides with a row that still exists, on every retry, forever - not merely a transient race).
   * {@code MAX(sequence)} has no such failure mode: a gap left by a deleted message is simply
   * skipped, never collided with.
   */
  @Query("select max(m.sequence) from ChatMessage m where m.chatId = :chatId")
  Integer findMaxSequenceByChatId(@Param("chatId") UUID chatId);
}
