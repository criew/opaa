package io.opaa.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AgentPlanStep;
import io.opaa.query.toolloop.AgentToolLoopProperties;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The delivered agent "Recherche" as #3674 configures it: every tool, a plan to confirm, its
 * contradictions named, no fixed library, and limits above a self-made agent's default but within
 * the installation's limits.
 */
class AgentResearchConfigurationTest {

  private final AgentConfiguration research = AgentResearchProvisioner.researchConfiguration();

  @Test
  void itOffersEveryToolAndAsksForConfirmationOfItsPlan() {
    assertThat(research.toolNames()).containsExactlyInAnyOrderElementsOf(AgentTools.ASSIGNABLE);
    assertThat(research.planStep()).isEqualTo(AgentPlanStep.CONFIRM);
    assertThat(research.libraryIds()).isEmpty();
    assertThat(research.templateIds()).isEmpty();
  }

  @Test
  void itsInstructionAsksForSourcesAndNamedContradictions() {
    assertThat(research.instruction())
        .contains("Fundstelle")
        .contains("Widersprüche")
        .contains("Vorlage");
  }

  @Test
  void aSelfMadeAgentsDefaultLimitsEqualWhatARunTakesForAnAgentWithoutValues() {
    io.opaa.query.toolloop.AgentLimits run = io.opaa.query.toolloop.AgentLimits.DEFAULT;
    AgentLimits own = AgentService.DEFAULT_LIMITS;

    assertThat(own.maxToolCalls()).isEqualTo(run.maxToolCalls());
    assertThat(own.maxSearchCalls()).isEqualTo(run.maxSearchCalls());
    assertThat(own.maxDurationSeconds()).isEqualTo((int) run.maxDuration().toSeconds());
    assertThat(own.maxCollectedChars()).isEqualTo(run.maxCollectedChars());
  }

  @Test
  void itsInstructionCoversEverySubAspectAndSetsACitationMarkerOnEveryStatement() {
    assertThat(research.instruction())
        .contains("Teilfrage")
        .contains("eigenen, gezielten Suche")
        .contains("Prüfe vor der Antwort")
        .contains("hinter jede Aussage")
        .contains("Zitiermarke");
    assertThat(research.instruction().length())
        .isLessThanOrEqualTo(AgentService.MAX_INSTRUCTION_LENGTH);
  }

  @Test
  void nothingPromisesDeepResearch() {
    String delivered =
        String.join(
                " ",
                AgentResearchProvisioner.RESEARCH_NAME,
                AgentResearchProvisioner.RESEARCH_PURPOSE,
                research.instruction())
            .toLowerCase(Locale.ROOT);
    assertThat(delivered).doesNotContain("deep research").doesNotContain("deep-research");
    assertThat(AgentResearchProvisioner.RESEARCH_NAME).isEqualTo("Recherche");
  }

  @Test
  void itsLimitsLieAboveASelfMadeAgentsDefaultAndWithinTheInstallationsLimits() {
    AgentLimits own = AgentService.DEFAULT_LIMITS;
    AgentLimits limits = research.limits();
    AgentToolLoopProperties system =
        new Binder(new MapConfigurationPropertySource())
            .bindOrCreate("opaa.agent", AgentToolLoopProperties.class);

    assertThat(limits.maxToolCalls())
        .isGreaterThan(own.maxToolCalls())
        .isLessThanOrEqualTo(system.maxToolCalls());
    assertThat(limits.maxSearchCalls())
        .isGreaterThan(own.maxSearchCalls())
        .isLessThanOrEqualTo(system.maxSearchCalls());
    assertThat(limits.maxDurationSeconds())
        .isGreaterThan(own.maxDurationSeconds())
        .isLessThanOrEqualTo((int) system.maxDuration().toSeconds());
    assertThat(limits.maxCollectedChars())
        .isGreaterThan(own.maxCollectedChars())
        .isLessThanOrEqualTo(system.maxCollectedChars());
    assertThat(own.maxToolCalls()).isLessThan(system.maxToolCalls());
  }
}
