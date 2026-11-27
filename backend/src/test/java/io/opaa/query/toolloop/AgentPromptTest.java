package io.opaa.query.toolloop;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.query.answer.AnswerRules;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentPromptTest {

  /**
   * The agent answers by the same rules as the chat - answer first, "not contained" only when no
   * source contributes, no premise correction without a contradicting source, the reference value
   * from the source - taken from the one shared text, not worded a second time.
   */
  @Test
  void theFixedRulesCarryTheSharedAnswerRulesOfTheChat() {
    String system = AgentPrompt.systemText("b1", null, null, List.of());

    String answerRules =
        system.substring(system.indexOf("ANTWORTREGELN"), system.indexOf("ZITIERREGELN"));
    assertThat(answerRules)
        .contains(AnswerRules.SHARED)
        .contains("(deine Quellen)")
        .contains("search_knowledge");
    assertThat(system).containsOnlyOnce("in den vorliegenden Dokumenten nicht enthalten");
  }
}
