package io.opaa.query.retrieval.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * The anchor-space invariant of {@link DecompositionContext} (#1486, #1487,
 * docs/features/conversation-memory.md): a context building block reaches the model and the anchor
 * space in one step, and the anchor space holds the block's material without its own boilerplate.
 */
class DecompositionContextTest {

  private static final String INSTRUCTION = "Zerlege die Frage.";

  private static final String BLOCK_HEADING = "Angaben der fragenden Person:";

  private static final List<Message> WINDOW =
      List.of(
          new UserMessage("Was kostet ein Anwohnerparkausweis?"),
          new AssistantMessage("Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr."));

  private static DecompositionContext withBlock(String question, String... points) {
    List<String> anchorTexts = List.of(points);
    String modelText =
        BLOCK_HEADING + anchorTexts.stream().collect(Collectors.joining("\n- ", "\n- ", ""));
    return DecompositionContext.of(question, WINDOW).withContextBlock(modelText, anchorTexts);
  }

  /**
   * Everything anchored was rendered, and every piece of <em>material</em> rendered is anchored - a
   * rendered point left out would make a correctly enriched sub-query look unrelated, and the belt
   * is all-or-nothing.
   */
  @Test
  void theAnchorSpaceIsTheMaterialOfWhatIsRenderedToTheModel() {
    DecompositionContext context = withBlock("Was kostet der Ausweis?", "Bezugsjahr 2024");

    String renderedPrompt =
        context.systemText(INSTRUCTION)
            + "\n"
            + context.promptMessages().stream()
                .map(Message::getText)
                .collect(Collectors.joining("\n"));

    assertThat(context.contextTexts())
        .as("nothing anchors that the model was never given")
        .allSatisfy(text -> assertThat(renderedPrompt).contains(text));
    assertThat(context.contextTexts())
        .as("no material the model was given stays out of the anchor space")
        .containsExactlyInAnyOrderElementsOf(
            Stream.concat(
                    context.promptMessages().stream().map(Message::getText),
                    context.contextBlocks().stream().flatMap(block -> block.anchorTexts().stream()))
                .toList());
  }

  /**
   * Regression guard for #1487: a block's own heading reaches the model but never the anchor space.
   * {@code QueryDecompositionService#isRelated} matches on substring containment from four
   * characters, so a heading word like "Person" would relate a degenerate sub-query
   * ("Personalausweis beantragen") to any conversation that merely happens to carry a note - the
   * belt would stop firing exactly where it exists to fire.
   */
  @Test
  void aBlocksOwnHeadingReachesTheModelButNotTheAnchorSpace() {
    DecompositionContext context = withBlock("Was kostet der Ausweis?", "Bezugsjahr 2024");

    assertThat(context.systemText(INSTRUCTION)).contains(BLOCK_HEADING);
    assertThat(context.contextTexts())
        .as("the heading is OPAA's own wording, not material of the asking person")
        .noneSatisfy(text -> assertThat(text).contains("Angaben der fragenden Person"));
    assertThat(context.contextTexts()).contains("Bezugsjahr 2024");
  }

  /**
   * Without a block the system text is the bare instruction - nothing is rendered speculatively.
   */
  @Test
  void withoutAContextBlockTheSystemTextIsTheInstructionItself() {
    DecompositionContext context = DecompositionContext.of("Frage?", WINDOW);

    assertThat(context.systemText(INSTRUCTION)).isEqualTo(INSTRUCTION);
    assertThat(context.contextTexts())
        .containsExactly(
            "Was kostet ein Anwohnerparkausweis?",
            "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr.",
            "Frage?");
  }

  /** The question is the last message of the call, after the window it resolves against. */
  @Test
  void thePromptMessagesEndWithTheQuestion() {
    DecompositionContext context = DecompositionContext.of("Frage?", WINDOW);

    assertThat(context.promptMessages())
        .extracting(Message::getText)
        .containsExactly(
            "Was kostet ein Anwohnerparkausweis?",
            "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr.",
            "Frage?");
  }

  @Test
  void addingABlockLeavesTheOriginalContextUntouched() {
    DecompositionContext original = DecompositionContext.of("Frage?", WINDOW);

    DecompositionContext enriched =
        original.withContextBlock("Notiz:\n- Bezugsjahr 2024", List.of("Bezugsjahr 2024"));

    assertThat(original.contextBlocks()).isEmpty();
    assertThat(enriched.contextBlocks())
        .singleElement()
        .satisfies(
            block -> {
              assertThat(block.modelText()).isEqualTo("Notiz:\n- Bezugsjahr 2024");
              assertThat(block.anchorTexts()).containsExactly("Bezugsjahr 2024");
            });
  }
}
