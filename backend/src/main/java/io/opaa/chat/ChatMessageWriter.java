package io.opaa.chat;

import io.opaa.api.types.ChatRole;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write-phase persistence unit of one {@link ChatService#appendTurn} retry attempt (#889).
 * {@link #writeTurnOnce}'s {@link Propagation#REQUIRES_NEW} guarantees a failed attempt's rollback
 * never poisons a later retry attempt sharing one physical transaction - it says nothing about
 * connection count on its own; {@code appendTurn}'s own {@code Propagation.NOT_SUPPORTED} is what
 * guarantees this method's transaction is never a <em>second</em> connection held alongside an
 * ambient one (the #299/#525 deadlock).
 */
@Service
class ChatMessageWriter {

  private final ChatMessageRepository chatMessageRepository;
  private final ChatRepository chatRepository;
  private final ChatPersonalMarkRepository chatPersonalMarkRepository;

  ChatMessageWriter(
      ChatMessageRepository chatMessageRepository,
      ChatRepository chatRepository,
      ChatPersonalMarkRepository chatPersonalMarkRepository) {
    this.chatMessageRepository = chatMessageRepository;
    this.chatRepository = chatRepository;
    this.chatPersonalMarkRepository = chatPersonalMarkRepository;
  }

  /**
   * Inserts the question/answer pair at the next free sequence and applies the two atomic, targeted
   * {@code UPDATE}s {@link ChatRepository}'s Javadoc documents (title-from-first-question fallback,
   * {@code updated_at} touch) - see {@link ChatService#appendTurn} for the retry loop and
   * rollback-isolation reasoning around this call. The sender's own message brings the chat back
   * from the sender's chat archive, in the same transaction as the turn; nobody else's archive
   * changes.
   *
   * @return true if this turn was the chat's very first ({@code nextSequence == 0})
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  boolean writeTurnOnce(
      UUID chatId,
      UUID senderId,
      String question,
      String answer,
      String serializedSources,
      String derivedTitle) {
    int nextSequence = nextSequenceFor(chatId);
    chatMessageRepository.save(
        new ChatMessage(chatId, nextSequence, ChatRole.USER, question, null));
    chatMessageRepository.save(
        new ChatMessage(chatId, nextSequence + 1, ChatRole.ASSISTANT, answer, serializedSources));
    chatRepository.deriveTitleFromFirstQuestionIfAbsent(chatId, derivedTitle);
    chatRepository.touch(chatId, Instant.now());
    if (chatPersonalMarkRepository.clearArchive(chatId, senderId) > 0) {
      chatPersonalMarkRepository.deleteIfUnmarked(chatId, senderId);
    }
    return nextSequence == 0;
  }

  /**
   * {@code MAX(sequence) + 1}, not a row count (#889) - tolerates a gap left by a deleted message
   * without colliding. Not a locking read: two concurrent turns on the same chat can still compute
   * the same value, which {@link ChatService#appendTurn}'s retry loop resolves.
   */
  private int nextSequenceFor(UUID chatId) {
    Integer maxSequence = chatMessageRepository.findMaxSequenceByChatId(chatId);
    return maxSequence == null ? 0 : maxSequence + 1;
  }
}
