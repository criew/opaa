package io.opaa.query.toolloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A question back ends the run (ADR-0070, Entscheidung 1); the first question stays. */
class AskUserToolTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final AskUserTool tool = new AskUserTool();

  @Test
  void aQuestionIsKeptOnTheRunAndTheModelIsToldTheRunEnds() {
    AgentRun run = AgentRuns.run(Set.of(UUID.randomUUID()));

    ToolCallOutcome outcome = tool.call(args("{\"frage\":\"  Für welches Jahr?  \"}"), run);
    tool.call(args("{\"frage\":\"Zweite Frage\"}"), run);

    assertThat(outcome.result()).isEqualTo(ToolCallResult.SUCCESS);
    assertThat(outcome.modelText()).isEqualTo(AskUserTool.ASKED);
    assertThat(run.question()).isEqualTo("Für welches Jahr?");
  }

  /**
   * Regression guard for ADR-0070, Entscheidung 3: an id the model adds reaches nothing, the tool
   * reads no bestand.
   */
  @Test
  void aForeignIdAsArgumentReachesNothing() {
    AgentRun run = AgentRuns.run(Set.of(UUID.randomUUID()));

    ToolCallOutcome outcome =
        tool.call(
            args("{\"frage\":\"Welche Akte?\",\"bibliothek\":\"" + UUID.randomUUID() + "\"}"), run);

    assertThat(outcome.passages()).isEmpty();
    assertThat(outcome.hits()).isNull();
    assertThat(run.searchScope()).hasSize(1);
  }

  @Test
  void aMissingOrTooLongQuestionIsRefusedAndDoesNotEndTheRun() {
    AgentRun run = AgentRuns.run(Set.of(UUID.randomUUID()));

    assertThat(tool.call(args("{}"), run).result()).isEqualTo(ToolCallResult.INVALID_ARGUMENTS);
    assertThat(tool.call(args("{\"frage\":\"   \"}"), run).result())
        .isEqualTo(ToolCallResult.INVALID_ARGUMENTS);
    assertThat(tool.call(args("{\"frage\":\"" + "x".repeat(1001) + "\"}"), run).result())
        .isEqualTo(ToolCallResult.INVALID_ARGUMENTS);
    assertThat(run.question()).isNull();
  }

  private static JsonNode args(String json) {
    return JSON.readTree(json);
  }
}
