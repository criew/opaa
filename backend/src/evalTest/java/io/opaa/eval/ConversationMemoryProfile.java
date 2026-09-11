package io.opaa.eval;

import io.opaa.query.QueryProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * The three conversation-memory dimensions a multi-turn run is measured under (issue #1484,
 * docs/features/conversation-memory.md, "Konfiguration: Ebenenzuordnung") - fixed points of every
 * conversation report and baseline, because each of them moves what the decomposition sees and
 * therefore what the numbers mean.
 *
 * @param windowMessages the width of the conversation window in messages, <b>measured</b> from the
 *     production {@link ChatMemory} bean rather than read from a constant - see {@link
 *     #measuredFrom}.
 * @param searchWindowTurns the number of most recent turns the sub-question decomposition sees,
 *     read from the production {@link QueryProperties} - {@link
 *     #SEARCH_WINDOW_WHOLE_CONVERSATION_WINDOW} would mean production hands it the whole window
 *     instead.
 * @param noteCap the maximum number of Gesprächsnotiz points per chat, {@link
 *     #NO_CONVERSATION_NOTE} while there is no note.
 */
public record ConversationMemoryProfile(int windowMessages, int searchWindowTurns, int noteCap) {

  /**
   * The value {@link #searchWindowTurns} carried while the decomposition still received the entire
   * conversation window, before {@code opaa.query.search-window-turns} existed. Kept as a named
   * value because every baseline drawn before that change reports it, and a reader comparing two
   * reports has to be able to tell "whole window" from a configured {@code 0}, which today means
   * "question only".
   */
  public static final int SEARCH_WINDOW_WHOLE_CONVERSATION_WINDOW = 0;

  /** The value of {@link #noteCap} while no Gesprächsnotiz exists. */
  public static final int NO_CONVERSATION_NOTE = 0;

  /**
   * More messages than any plausible window keeps, handed to the probe below. Large enough that a
   * window width is actually measured rather than merely echoed back.
   */
  private static final int PROBE_MESSAGE_COUNT = 256;

  /**
   * Reads the profile off the running production configuration: the window width is measured by
   * handing the production {@link ChatMemory} bean more messages than it can hold and counting what
   * it returns, the search window is read from the production {@link QueryProperties} the pipeline
   * runs with. Measured rather than assumed, for the same reason every other fixed point of this
   * harness is - a changed production window must make the committed baseline incomparable, and a
   * constant copied into the harness would keep reporting the old number.
   *
   * <p>Runs under its own random conversation id and clears it afterwards, so it cannot touch a
   * measured conversation.
   */
  public static ConversationMemoryProfile measuredFrom(
      ChatMemory chatMemory, QueryProperties queryProperties) {
    String probeKey = "eval-window-probe-" + UUID.randomUUID();
    List<org.springframework.ai.chat.messages.Message> probe = new ArrayList<>(PROBE_MESSAGE_COUNT);
    for (int i = 0; i < PROBE_MESSAGE_COUNT; i++) {
      probe.add(new UserMessage("Sondierungsnachricht " + i));
    }
    try {
      chatMemory.add(probeKey, probe);
      int windowMessages = chatMemory.get(probeKey).size();
      if (windowMessages >= PROBE_MESSAGE_COUNT) {
        throw new IllegalStateException(
            "The production ChatMemory returned all "
                + PROBE_MESSAGE_COUNT
                + " probe messages, so no window width was measured. Either the window grew beyond "
                + "the probe size (raise PROBE_MESSAGE_COUNT) or the conversation window is "
                + "unbounded, in which case it is not a fixed point this path can report.");
      }
      return new ConversationMemoryProfile(
          windowMessages, queryProperties.searchWindowTurns(), NO_CONVERSATION_NOTE);
    } finally {
      chatMemory.clear(probeKey);
    }
  }
}
