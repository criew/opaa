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

  /**
   * The form rules of the shared answer rules - answer first, one paragraph on gaps - must not
   * override the form an agent instruction or the plan step prescribes, such as a report with gaps
   * per sub-question.
   */
  @Test
  void theAgentInstructionAndThePlanStepPrevailOverTheFormOfTheAnswerRules() {
    String system =
        AgentPrompt.systemText(
            "b1", "Gliedere nach Teilfragen.", null, List.of(), AgentPrompt.PlanPhase.PLAN);

    assertThat(AgentPrompt.AGENT_HEADER)
        .contains(
            "Form und Gliederung, die sie vorgibt, gehen den Formregeln der ANTWORTREGELN vor");
    assertThat(AgentPrompt.PLAN_RULES).contains("Die ANTWORTREGELN gelten erst für die Antwort");
    assertThat(system).contains(AgentPrompt.AGENT_HEADER).contains(AgentPrompt.PLAN_RULES);
  }
}
