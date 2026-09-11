package io.opaa.query.retrieval.search;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Everything one decomposition call hands the model besides its fixed instruction: the question,
 * the search window of the conversation, and any rendered context block
 * (docs/features/conversation-memory.md, "Bauteil 1").
 *
 * <p><b>Anchor-space invariant.</b> {@link #contextTexts()} is <em>the same</em> material {@link
 * #promptMessages()} and {@link #systemText(String)} render to the model, so {@code
 * QueryDecompositionService}'s safety belt anchors against exactly what the decomposition was given
 * - never against a separately maintained enumeration of sources. A context building block added to
 * this record therefore reaches the model and widens the anchor space in one step; it cannot reach
 * one without the other.
 */
public record DecompositionContext(
    String question, List<Message> searchWindow, List<String> contextBlocks) {

  public DecompositionContext {
    searchWindow = List.copyOf(searchWindow);
    contextBlocks = List.copyOf(contextBlocks);
  }

  /** A context of question and search window, without any rendered block. */
  public static DecompositionContext of(String question, List<Message> searchWindow) {
    return new DecompositionContext(question, searchWindow, List.of());
  }

  /**
   * The same context with one more rendered block appended - the seam the Gesprächsnotiz's {@code
   * RAHMEN} points enter through.
   */
  public DecompositionContext withContextBlock(String block) {
    List<String> blocks = new ArrayList<>(contextBlocks);
    blocks.add(block);
    return new DecompositionContext(question, searchWindow, blocks);
  }

  /** The chat messages of the call: the search window, then the question. */
  public List<Message> promptMessages() {
    List<Message> messages = new ArrayList<>(searchWindow.size() + 1);
    messages.addAll(searchWindow);
    messages.add(new UserMessage(question));
    return List.copyOf(messages);
  }

  /** {@code instruction} followed by the rendered context blocks, if any. */
  public String systemText(String instruction) {
    if (contextBlocks.isEmpty()) {
      return instruction;
    }
    return instruction + "\n" + String.join("\n", contextBlocks);
  }

  /**
   * Every text of this context, in rendering order - the anchor space of the safety belt. Holds the
   * texts of {@link #promptMessages()} and the blocks {@link #systemText(String)} appends, and
   * nothing else: the instruction is not context, and anchoring a sub-query against the prompt's
   * own German wording would relate every output to it.
   */
  public List<String> contextTexts() {
    List<String> texts = new ArrayList<>(searchWindow.size() + contextBlocks.size() + 1);
    searchWindow.forEach(message -> texts.add(message.getText()));
    texts.add(question);
    texts.addAll(contextBlocks);
    return List.copyOf(texts);
  }
}
