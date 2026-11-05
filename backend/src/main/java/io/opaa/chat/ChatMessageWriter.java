package io.opaa.chat;

import io.opaa.api.types.ChatRole;
import io.opaa.common.ValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write-phase persistence unit of one {@link ChatService#appendTurn} retry attempt. {@link
 * #writeTurnOnce}'s {@link Propagation#REQUIRES_NEW} guarantees a failed attempt's rollback never
 * poisons a later retry attempt sharing one physical transaction - it says nothing about connection
 * count on its own; {@code appendTurn}'s own {@code Propagation.NOT_SUPPORTED} is what guarantees
 * this method's transaction is never a <em>second</em> connection held alongside an ambient one,
 * which deadlocks the pool under load.
 */
@Service
class ChatMessageWriter {

  private final ChatMessageRepository chatMessageRepository;
  private final ChatRepository chatRepository;
  private final ChatPersonalMarkRepository chatPersonalMarkRepository;
  private final ChatAttachmentRepository chatAttachmentRepository;
  private final Clock clock;

  ChatMessageWriter(
      ChatMessageRepository chatMessageRepository,
      ChatRepository chatRepository,
      ChatPersonalMarkRepository chatPersonalMarkRepository,
      ChatAttachmentRepository chatAttachmentRepository,
      Clock clock) {
    this.chatAttachmentRepository = chatAttachmentRepository;
    this.chatMessageRepository = chatMessageRepository;
    this.chatRepository = chatRepository;
    this.chatPersonalMarkRepository = chatPersonalMarkRepository;
    this.clock = clock;
  }

  /**
   * Inserts the question/answer pair at the next free sequence and applies the two atomic, targeted
   * {@code UPDATE}s {@link ChatRepository}'s Javadoc documents (title-from-first-question fallback,
   * {@code updated_at} touch) - see {@link ChatService#appendTurn} for the retry loop and
   * rollback-isolation reasoning around this call. The sender's own message brings the chat back
   * from the sender's chat archive, in the same transaction as the turn; nobody else's archive
   * changes. {@code usedPrompt} - may be {@code null} - goes onto the question's message only, as
   * do {@code attachmentIds}, bound to the chat with the turn; {@code answerAttachmentIds} - files
   * a tool produced for the answer - go onto the answer's message the same way. One no longer
   * bindable (deleted, or bound elsewhere meanwhile) rolls the turn back.
   *
   * @return true if this turn was the chat's very first ({@code nextSequence == 0})
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  boolean writeTurnOnce(
      UUID chatId,
      UUID senderId,
      String question,
      UsedPrompt usedPrompt,
      String answer,
      String serializedSources,
      String serializedEvidence,
      String derivedTitle,
      Collection<UUID> attachmentIds,
      Collection<UUID> answerAttachmentIds) {
    int nextSequence = nextSequenceFor(chatId);
    ChatMessage questionMessage =
        chatMessageRepository.save(
            new ChatMessage(chatId, nextSequence, ChatRole.USER, question, null, usedPrompt));
    if (!attachmentIds.isEmpty()) {
      chatMessageRepository.flush();
      int bound =
          chatAttachmentRepository.bindToMessage(
              attachmentIds, senderId, chatId, questionMessage.getId());
      if (bound != attachmentIds.size()) {
        throw new ValidationException("Ein Anhang ist nicht mehr vorhanden");
      }
    }
    ChatMessage answerMessage =
        new ChatMessage(chatId, nextSequence + 1, ChatRole.ASSISTANT, answer, serializedSources);
    answerMessage.setAnswerEvidence(serializedEvidence);
    chatMessageRepository.save(answerMessage);
    if (!answerAttachmentIds.isEmpty()) {
      chatMessageRepository.flush();
      int bound =
          chatAttachmentRepository.bindToMessage(
              answerAttachmentIds, senderId, chatId, answerMessage.getId());
      if (bound != answerAttachmentIds.size()) {
        throw new ValidationException("Eine erzeugte Datei ist nicht mehr vorhanden");
      }
    }
    chatRepository.deriveTitleFromFirstQuestionIfAbsent(chatId, derivedTitle);
    chatRepository.touch(chatId, Instant.now(clock));
    if (chatPersonalMarkRepository.clearArchive(chatId, senderId) > 0) {
      chatPersonalMarkRepository.deleteIfUnmarked(chatId, senderId);
    }
    return nextSequence == 0;
  }

  /**
   * {@code MAX(sequence) + 1}, not a row count - tolerates a gap left by a deleted message without
   * colliding. Not a locking read: two concurrent turns on the same chat can still compute the same
   * value, which {@link ChatService#appendTurn}'s retry loop resolves.
   */
  private int nextSequenceFor(UUID chatId) {
    Integer maxSequence = chatMessageRepository.findMaxSequenceByChatId(chatId);
    return maxSequence == null ? 0 : maxSequence + 1;
  }
}
