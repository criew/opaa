package io.opaa.query.retrieval.search;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
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
 * never a superset - {@link ContextBlock} enforces that rather than assuming it. Leaving material
 * out of the anchor space narrows the belt in the overwhelming majority of cases and can never
 * widen it through text OPAA wrote itself; it is not, however, strictly monotone, because {@code
 * countUnrelated} skips its check entirely below two anchor tokens.
 *
 * <p><b>The search window reaches the model as material, not as a conversation.</b> {@link
 * #promptMessages()} renders window and question into one labelled user message. Handed over as
 * alternating user and assistant messages, a chat model reads long assistant answers as a
 * conversation it is part of and continues it - answering the question instead of reformulating it.
 * The labels are OPAA's own wording and stay out of the anchor space, like a block's heading.
 */
public record DecompositionContext(
    String question, List<Message> searchWindow, List<ContextBlock> contextBlocks) {

  /**
   * One rendered block: {@code modelText} is what the system prompt carries, {@code anchorTexts}
   * the parts of it the safety belt may anchor against. Both are produced by whoever renders the
   * block, in one step - see {@code io.opaa.query.ConversationNoteBlock}.
   *
   * <p><b>Every anchor text must occur verbatim in {@code modelText}.</b> Checked, not merely
   * documented: the subset relation is the whole reason the two halves may differ at all, and an
   * anchor text the model never saw would be exactly the separately maintained enumeration this
   * type exists to prevent - in the loosening direction, where the safety belt would legitimize
   * sub-queries against words nothing in the prompt carried.
   */
  public record ContextBlock(String modelText, List<String> anchorTexts) {
    public ContextBlock {
      anchorTexts = List.copyOf(anchorTexts);
      for (String anchorText : anchorTexts) {
        if (!modelText.contains(anchorText)) {
          throw new IllegalArgumentException(
              "anchor text is not part of the rendered block: " + anchorText);
        }
      }
    }
  }

  static final String WINDOW_LABEL = "Bisheriger Gesprächsverlauf:";

  static final String USER_LABEL = "Nutzer: ";

  static final String ASSISTANT_LABEL = "Assistent: ";

  static final String QUESTION_LABEL = "Aktuelle Nutzerfrage: ";

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

  /**
   * The chat messages of the call: exactly one user message carrying the labelled search window, if
   * any, followed by the labelled question - see this record's Javadoc on why not one message per
   * turn.
   */
  public List<Message> promptMessages() {
    String labelledQuestion = QUESTION_LABEL + question;
    if (searchWindow.isEmpty()) {
      return List.of(new UserMessage(labelledQuestion));
    }
    String window =
        searchWindow.stream()
            .map(message -> labelOf(message) + message.getText())
            .collect(Collectors.joining("\n\n"));
    return List.of(new UserMessage(WINDOW_LABEL + "\n" + window + "\n\n" + labelledQuestion));
  }

  private static String labelOf(Message message) {
    return message.getMessageType() == MessageType.ASSISTANT ? ASSISTANT_LABEL : USER_LABEL;
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
   * The anchor space of the safety belt, in rendering order: the texts of the search window, the
   * question, and the material of the blocks {@link #systemText(String)} appends - and nothing
   * else. Neither the instruction, nor the labels of {@link #promptMessages()}, nor a block's own
   * heading is context; anchoring a sub-query against the prompt's own German wording would relate
   * every output to it.
   */
  public List<String> contextTexts() {
    List<String> texts = new ArrayList<>(searchWindow.size() + contextBlocks.size() + 1);
    searchWindow.forEach(message -> texts.add(message.getText()));
    texts.add(question);
    contextBlocks.forEach(block -> texts.addAll(block.anchorTexts()));
    return List.copyOf(texts);
  }
}
