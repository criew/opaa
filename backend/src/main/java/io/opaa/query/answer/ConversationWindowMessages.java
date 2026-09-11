package io.opaa.query.answer;

import io.opaa.query.citation.CitationMarkers;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

/**
 * The two entrances into the conversation window, in one place
 * (docs/features/conversation-memory.md, "Bauteil 1"): {@link #answer} for the answer just
 * generated, {@link #reloaded} for the persisted history read back on a cache miss.
 *
 * <p>Both normalize identically - answers lose their citation markers, questions are the person's
 * own wording and stay untouched. That is the whole point of them living together: the same chat
 * must send the same prompt before and after a restart or a cache eviction, and two entrances
 * normalizing separately is exactly how that stops being true.
 */
public final class ConversationWindowMessages {

  private ConversationWindowMessages() {}

  /** The answer as the window holds it; the persisted text keeps its markers. */
  public static AssistantMessage answer(String answerText) {
    return new AssistantMessage(CitationMarkers.strip(answerText));
  }

  /** The persisted history as the window holds it, in the order it was given. */
  public static List<Message> reloaded(List<Message> persistedHistory) {
    return persistedHistory.stream()
        .map(message -> message instanceof AssistantMessage ? answer(message.getText()) : message)
        .toList();
  }
}
