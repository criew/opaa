package io.opaa.chat;

import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.observability.ChatMetrics;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Condenses the user message of a finished turn into zero to {@link
 * ChatNoteExtraction#MAX_POINTS_PER_TURN} Gesprächsnotiz points (#1487,
 * docs/features/conversation-memory.md, "Bauteil 2"), built on the same blueprint as {@link
 * ChatTitleGenerationService}: off the request thread, on the systemwide active chat model resolved
 * fresh per call, parsed defensively, and never surfacing a failure to anyone.
 *
 * <p><b>Only user messages, never answers.</b> A point derived from an answer would be an unsourced
 * claim in a system prompt that no Belegprüfung can reach anymore (ADR-0031, "Verworfene
 * Alternativen").
 *
 * <p><b>No retry, no catch-up.</b> A failed condensation means this turn contributes no points,
 * full stop - counter and log line remain, no state is kept, and the next turn condenses normally.
 * The alternative would need a mechanism surviving a restart and inventing a "next run" for the
 * last turn of a chat; the blueprint above has no second attempt either.
 *
 * <p><b>No self-invocation and no {@code @Transactional} here.</b> The write goes through {@link
 * ChatNoteService#append}, called directly on the injected bean, which carries the transaction
 * itself.
 */
@Service
public class ChatNoteExtractionService {

  private static final Logger log = LoggerFactory.getLogger(ChatNoteExtractionService.class);

  private final ActiveChatModelResolver activeChatModelResolver;
  private final ChatNoteService chatNoteService;
  private final ChatService chatService;
  private final ChatMetrics metrics;

  public ChatNoteExtractionService(
      ActiveChatModelResolver activeChatModelResolver,
      ChatNoteService chatNoteService,
      ChatService chatService,
      ChatMetrics metrics) {
    this.activeChatModelResolver = activeChatModelResolver;
    this.chatNoteService = chatNoteService;
    this.chatService = chatService;
    this.metrics = metrics;
  }

  /**
   * Condenses {@code userMessage} and appends what the note accepts. Runs after the answer has
   * already been returned and the turn persisted, so the person's thinking time hides the latency
   * and a question arriving before this finishes simply runs on the previous state - harmless,
   * because that turn is still in the conversation window verbatim.
   */
  @Async("chatNoteTaskExecutor")
  public void condenseAsync(UUID chatId, UUID spaceId, String userMessage) {
    try {
      List<ChatNoteCandidate> candidates = condense(userMessage);
      if (candidates.isEmpty()) {
        metrics.recordEmptyNoteExtraction();
        return;
      }
      // The space can be archived between the answer and this write; an archived space accepts no
      // change to an existing chat, so the result is thrown away rather than written. Deliberately
      // the guard ChatService#appendTurn already uses, not a second reading of the same rule.
      if (spaceArchivedOrGone(spaceId)) {
        metrics.recordDiscardedNoteExtraction();
        log.debug(
            "Chat note condensation discarded for chat {} - its space was archived in the meantime",
            chatId);
        return;
      }
      if (chatNoteService.append(chatId, candidates).isEmpty()) {
        metrics.recordEmptyNoteExtraction();
        return;
      }
      metrics.recordAppliedNoteExtraction();
    } catch (RuntimeException e) {
      // Covers the model call and the write alike. No content in the message: the condensed text
      // is what the person wrote, which docs/features/security-and-compliance.md keeps out of the
      // application log.
      metrics.recordFailedNoteExtraction();
      log.warn(
          "Chat note condensation failed for chat {} - this turn contributes no note points and is"
              + " not retried",
          chatId,
          e);
    }
  }

  /**
   * The model call and the parsing alone, synchronous and without any persistence - the unit the
   * multi-turn evaluation harness drives, so the note it measures is produced by the production
   * condensation rather than by a scripted stand-in. Throws whatever the model call throws.
   */
  public List<ChatNoteCandidate> condense(String userMessage) {
    if (userMessage == null || userMessage.isBlank()) {
      return List.of();
    }
    ChatClient chatClient = activeChatModelResolver.resolveChatClient();
    ChatResponse response =
        chatClient.prompt().user(ChatNoteExtraction.prompt(userMessage)).call().chatResponse();
    if (response == null
        || response.getResult() == null
        || response.getResult().getOutput() == null) {
      return List.of();
    }
    return ChatNoteExtraction.parse(response.getResult().getOutput().getText());
  }

  private boolean spaceArchivedOrGone(UUID spaceId) {
    try {
      chatService.requireSpaceNotArchived(spaceId);
      return false;
    } catch (ConflictException | NotFoundException e) {
      return true;
    }
  }
}
