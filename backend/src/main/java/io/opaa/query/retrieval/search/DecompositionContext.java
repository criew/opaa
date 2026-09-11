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
 * <p><b>Anchor-space invariant.</b> A context building block reaches the model and the anchor space
 * of {@code QueryDecompositionService}'s safety belt in <em>one</em> step - {@link
 * #withContextBlock(String, List)} takes both halves together, so a block cannot reach one without
 * the other, and neither half can come from a separately maintained enumeration.
 *
 * <p><b>The anchor space is the block's material, not its boilerplate.</b> A block renders content
 * inside a heading of its own; only the content is material of the asking person. The belt matches
 * on substring containment from four characters ({@code QueryDecompositionService#isRelated}), so a
 * heading word in the anchor space would relate sub-queries to the prompt's own German wording -
 * "Personalausweis beantragen" would count as related to any conversation merely because the
 * heading contains "Person". {@link #contextTexts()} is therefore a subset of what the model sees,
 * never a superset: leaving material out of the anchor space can only make the belt stricter, and
 * the one thing it must never do is grow looser through text OPAA wrote itself.
 */
public record DecompositionContext(
    String question, List<Message> searchWindow, List<ContextBlock> contextBlocks) {

  /**
   * One rendered block: {@code modelText} is what the system prompt carries, {@code anchorTexts}
   * the parts of it the safety belt may anchor against. Both are produced by whoever renders the
   * block, in one step - see {@code io.opaa.query.ConversationNoteBlock}.
   */
  public record ContextBlock(String modelText, List<String> anchorTexts) {
    public ContextBlock {
      anchorTexts = List.copyOf(anchorTexts);
    }
  }

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
   *
   * @param modelText the block as the system prompt carries it, heading included
   * @param anchorTexts the material of that block, without its heading - see this record's Javadoc
   *     on why the two differ
   */
  public DecompositionContext withContextBlock(String modelText, List<String> anchorTexts) {
    List<ContextBlock> blocks = new ArrayList<>(contextBlocks);
    blocks.add(new ContextBlock(modelText, anchorTexts));
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
    StringBuilder text = new StringBuilder(instruction);
    contextBlocks.forEach(block -> text.append("\n").append(block.modelText()));
    return text.toString();
  }

  /**
   * The anchor space of the safety belt, in rendering order: the texts of {@link #promptMessages()}
   * and the material of the blocks {@link #systemText(String)} appends - and nothing else. Neither
   * the instruction nor a block's own heading is context; anchoring a sub-query against the
   * prompt's own German wording would relate every output to it.
   */
  public List<String> contextTexts() {
    List<String> texts = new ArrayList<>(searchWindow.size() + contextBlocks.size() + 1);
    searchWindow.forEach(message -> texts.add(message.getText()));
    texts.add(question);
    contextBlocks.forEach(block -> texts.addAll(block.anchorTexts()));
    return List.copyOf(texts);
  }
}
