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
 * The anchor-space invariant of {@link DecompositionContext} (#1486,
 * docs/features/conversation-memory.md): what the model is given and what the safety belt anchors
 * against are the same material, derived from one object.
 */
class DecompositionContextTest {

  private static final String INSTRUCTION = "Zerlege die Frage.";

  private static final List<Message> WINDOW =
      List.of(
          new UserMessage("Was kostet ein Anwohnerparkausweis?"),
          new AssistantMessage("Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr."));

  /**
   * The anchor space is exactly the rendered context - no more (the instruction is not context) and
   * no less (a rendered block left out would make a correctly enriched sub-query look unrelated,
   * and the belt is all-or-nothing).
   */
  @Test
  void theAnchorSpaceIsExactlyWhatIsRenderedToTheModel() {
    DecompositionContext context =
        DecompositionContext.of("Was kostet der Ausweis?", WINDOW)
            .withContextBlock("Bezugsjahr 2024");

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
        .as("nothing the model was given stays out of the anchor space")
        .containsExactlyInAnyOrderElementsOf(
            Stream.concat(
                    context.promptMessages().stream().map(Message::getText),
                    context.contextBlocks().stream())
                .toList());
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

    DecompositionContext enriched = original.withContextBlock("Bezugsjahr 2024");

    assertThat(original.contextBlocks()).isEmpty();
    assertThat(enriched.contextBlocks()).containsExactly("Bezugsjahr 2024");
  }
}
