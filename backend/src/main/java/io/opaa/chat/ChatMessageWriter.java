package io.opaa.chat;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write-phase persistence unit of one {@link ChatService#appendTurn} retry attempt (#889).
 * {@link #writeTurnOnce}'s {@link Propagation#REQUIRES_NEW} guarantees a failed attempt's rollback
 * never poisons a later retry attempt sharing one physical transaction, regardless of whether the
 * caller happens to carry an ambient transaction of its own.
 */
@Service
class ChatMessageWriter {

  private final ChatMessageRepository chatMessageRepository;
  private final ChatRepository chatRepository;

  ChatMessageWriter(ChatMessageRepository chatMessageRepository, ChatRepository chatRepository) {
    this.chatMessageRepository = chatMessageRepository;
    this.chatRepository = chatRepository;
  }

  /**
   * Inserts the question/answer pair at the next free sequence and applies the two atomic, targeted
   * {@code UPDATE}s {@link ChatRepository}'s Javadoc documents (title-from-first-question fallback,
   * {@code updated_at} touch) - see {@link ChatService#appendTurn} for the retry loop and
   * rollback-isolation reasoning around this call.
   *
   * @return true if this turn was the chat's very first ({@code nextSequence == 0})
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  boolean writeTurnOnce(
      UUID chatId, String question, String answer, String serializedSources, String derivedTitle) {
    int nextSequence = nextSequenceFor(chatId);
    chatMessageRepository.save(
        new ChatMessage(chatId, nextSequence, ChatRole.USER, question, null));
    chatMessageRepository.save(
        new ChatMessage(chatId, nextSequence + 1, ChatRole.ASSISTANT, answer, serializedSources));
    chatRepository.deriveTitleFromFirstQuestionIfAbsent(chatId, derivedTitle);
    chatRepository.touch(chatId, Instant.now());
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
