package io.opaa.query.answer;

import io.opaa.query.citation.CitationMarkers;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

/**
 * The two entrances into the conversation window, in one place
 * (docs/features/conversation-memory.md, "Bauteil 1"): {@link #answer} for the answer just
 * generated, {@link #reloaded} for the persisted history read back on a cache miss.
 *
 * <p>Both normalize identically - answers lose their citation markers, questions are the person's
 * own wording and stay untouched, and an answer that is nothing but markers yields no window
 * message at all. That is the whole point of them living together: the same chat must send the same
 * prompt before and after a restart or a cache eviction, and two entrances normalizing separately
 * is exactly how that stops being true.
 */
public final class ConversationWindowMessages {

  private ConversationWindowMessages() {}

  /**
   * The answer as the window holds it, or empty when nothing but markers and whitespace remained.
   * An empty assistant message is rejected with 400 by some providers, which would break every
   * following turn of that chat - and the reload path would reproduce it from the persisted text,
   * so it would survive a restart. The persisted text itself keeps its markers.
   */
  public static Optional<AssistantMessage> answer(String answerText) {
    String withoutMarkers = CitationMarkers.strip(answerText);
    return withoutMarkers == null || withoutMarkers.isBlank()
        ? Optional.empty()
        : Optional.of(new AssistantMessage(withoutMarkers));
  }

  /** The persisted history as the window holds it, in the order it was given. */
  public static List<Message> reloaded(List<Message> persistedHistory) {
    List<Message> window = new ArrayList<>(persistedHistory.size());
    for (Message message : persistedHistory) {
      if (message instanceof AssistantMessage) {
        answer(message.getText()).ifPresent(window::add);
      } else {
        window.add(message);
      }
    }
    return List.copyOf(window);
  }
}
