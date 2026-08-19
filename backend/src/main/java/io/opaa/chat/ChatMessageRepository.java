package io.opaa.chat;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

  /**
   * Ordered by the application-assigned {@code sequence}, not {@code created_at} - see {@link
   * ChatMessage}'s Javadoc for why timestamp ordering alone is not reliable for two messages of the
   * same turn.
   */
  List<ChatMessage> findByChatIdOrderBySequenceAsc(UUID chatId);

  int countByChatId(UUID chatId);
}
