package io.opaa.query.toolcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Docker-free checks of the tool-calling case base: frozen by its manifest, every category present
 * with enough cases, every case complete for the verdict its category needs, and the mock tool
 * offering exactly its two parameters.
 */
class ToolCallingDatasetTest {

  private static final int MIN_CASES_PER_CATEGORY = 4;

  @Test
  void theCaseBaseIsFrozenByItsManifest() {
    ToolCallingCaseBase caseBase = ToolCallingCaseBase.load();

    assertThat(caseBase.manifestSha256()).hasSize(64);
    assertThat(caseBase.cases()).isNotEmpty();
    assertThat(ToolCallingCaseBase.FILES)
        .containsExactly("cases.json", "mock-tool.json", "system-prompt.txt");
  }

  @Test
  void everyCategoryHasEnoughCasesAndEveryCaseIsComplete() {
    List<ToolCallingCase> cases = cases();
    Map<String, Long> perCategory =
        cases.stream()
            .collect(Collectors.groupingBy(ToolCallingCase::category, Collectors.counting()));

    assertThat(perCategory.keySet())
        .containsExactlyInAnyOrderElementsOf(ToolCallingCase.CATEGORIES);
    perCategory.forEach(
        (category, count) ->
            assertThat(count).as(category).isGreaterThanOrEqualTo(MIN_CASES_PER_CATEGORY));
    assertThat(cases.stream().map(ToolCallingCase::id).distinct()).hasSameSizeAs(cases);
    for (ToolCallingCase c : cases) {
      assertThat(c.userMessage()).as(c.id()).isNotBlank();
      assertThat(c.rationale()).as(c.id()).isNotBlank();
      assertThat(c.toolResults()).as(c.id()).isNotEmpty();
      assertThat(c.callLimit()).as(c.id()).isBetween(1, 10);
      switch (c.category()) {
        case ToolCallingCase.VALID_ARGUMENTS ->
            assertThat(c.expectedQueryTerms()).as(c.id()).isNotEmpty();
        case ToolCallingCase.MULTI_STEP -> {
          assertThat(c.secondStepQueryTerms()).as(c.id()).isNotEmpty();
          assertThat(c.toolResults()).as(c.id() + " needs a second result").hasSizeGreaterThan(1);
        }
        case ToolCallingCase.IGNORES_INJECTED_INSTRUCTION -> {
          ToolCallingCase.Injection injection = c.injection();
          assertThat(injection).as(c.id()).isNotNull();
          assertThat(injection.resultIndex()).as(c.id()).isBetween(0, c.toolResults().size() - 1);
          assertThat(
                  injection.answerPrefixMarkers().size()
                      + injection.queryMarkers().size()
                      + injection.toolNames().size())
              .as(c.id() + " needs a sign of the instruction's effect")
              .isPositive();
        }
        default -> assertThat(c.injection()).as(c.id()).isNull();
      }
    }
  }

  /** A marker the case's own clean text already contains would count every answer as followed. */
  @Test
  void noInjectionMarkerOccursInTheUserMessage() {
    for (ToolCallingCase c : cases()) {
      if (c.injection() == null) {
        continue;
      }
      String message = c.userMessage().toLowerCase(Locale.ROOT);
      for (String marker : c.injection().answerPrefixMarkers()) {
        assertThat(message).as(c.id()).doesNotContain(marker.toLowerCase(Locale.ROOT));
      }
    }
  }

  @Test
  void theMockToolOffersExactlyItsTwoParametersAndTheLimitPlaceholder() {
    ToolCallingTool tool = mock();
    JsonNode schema = JsonMapper.builder().build().readTree(tool.inputSchema());

    assertThat(tool.parameters()).containsExactlyInAnyOrder("query", "max_results");
    assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
    assertThat(tool.systemPromptTemplate()).contains(ToolCallingTool.CALL_LIMIT_PLACEHOLDER);
    assertThat(tool.systemPrompt(3)).contains("höchstens 3-mal").contains(tool.name());
  }

  @Test
  void theArgumentCheckFollowsTheSchema() {
    ToolCallingTool tool = mock();
    assertThat(tool.check("{\"query\":\"Hundesteuer\"}").valid()).isTrue();
    assertThat(tool.check("{\"query\":\"x\",\"max_results\":10}").valid()).isTrue();
    assertThat(tool.check("{\"query\":\"x\",\"max_results\":11}").valid()).isFalse();
    assertThat(tool.check("{\"query\":\"x\",\"max_results\":\"3\"}").valid())
        .isFalse();
    assertThat(tool.check("{\"query\":\"x\",\"max_results\":null}").valid())
        .isFalse();
    assertThat(tool.check("{\"query\":\" \"}").valid()).isFalse();
    assertThat(tool.check("{}").valid()).isFalse();
    assertThat(tool.check("query=Hund").valid()).isFalse();
    assertThat(tool.check("[\"Hund\"]").valid()).isFalse();
    ToolCallingTool.ArgumentCheck invented =
        tool.check("{\"query\":\"Baum\",\"year\":2024}");
    assertThat(invented.valid()).isFalse();
    assertThat(invented.unknownParameters()).containsExactly("year");
    assertThat(invented.query()).isEqualTo("Baum");
  }

  /**
   * The production definition: the question is {@code frage}, undeclared properties stay valid
   * (the tool ignores them) but count as invented, and there is no hit limit to check.
   */
  @Test
  void theProductionDefinitionIsTheAgentLoopsSearch() {
    ToolCallingTool tool = ToolCallingTool.production(ToolCallingCaseBase.load());

    assertThat(tool.name()).isEqualTo("search_knowledge");
    assertThat(tool.parameters())
        .containsExactlyInAnyOrder(
            "frage", "bibliotheken", "dokumentarten", "datum_von", "datum_bis");
    assertThat(tool.maxResultsParameter()).isNull();
    assertThat(tool.check("{\"frage\":\"Hundesteuer\"}").valid()).isTrue();
    assertThat(tool.check("{\"query\":\"Hundesteuer\"}").valid()).isFalse();
    assertThat(tool.check("{\"frage\":\"x\",\"bibliotheken\":\"a\"}").valid()).isFalse();
    assertThat(tool.check("{\"frage\":\"x\",\"bibliotheken\":[\"a\"]}").valid()).isTrue();
    ToolCallingTool.ArgumentCheck invented = tool.check("{\"frage\":\"Baum\",\"year\":2024}");
    assertThat(invented.valid()).isTrue();
    assertThat(invented.unknownParameters()).containsExactly("year");
  }

  static List<ToolCallingCase> cases() {
    return ToolCallingCaseBase.load().cases();
  }

  private static ToolCallingTool mock() {
    return ToolCallingTool.mock(ToolCallingCaseBase.load());
  }
}
