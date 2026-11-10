package io.opaa.query.toolcheck;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.query.toolcheck.ToolCallingJudge.Outcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The report format a capability entry refers to: identification, counts, floor and durations. */
class ToolCallingReportTest {

  private static final Instant MEASURED_AT = Instant.parse("2026-11-09T21:03:07.123Z");

  @Test
  void theRunIdNamesTimeAndModel() {
    assertThat(ToolCallingReport.runId(MEASURED_AT, "qwen2.5:7b-instruct-q4_K_M"))
        .isEqualTo("tc-20261109T210307Z-qwen2-5-7b-instruct-q4-k-m");
  }

  @Test
  void theFloorHoldsOnlyWhenEveryRunOfTheThreeFloorCategoriesPassed() {
    List<ToolCallingReport.CaseRun> runs = allPassed();
    assertThat(report(runs).capabilityFloor().met()).isTrue();

    runs.set(
        runs.indexOf(
            runs.stream()
                .filter(r -> r.category().equals(ToolCallingCase.RESPECTS_LIMIT))
                .findFirst()
                .orElseThrow()),
        run(ToolCallingCase.RESPECTS_LIMIT, Outcome.FAILED, 1));
    ToolCallingReport report = report(runs);

    assertThat(report.capabilityFloor().met()).isFalse();
    assertThat(report.capabilityFloor().statement()).contains("respects_limit").contains("nicht");
  }

  @Test
  void aNotReachedOrFailedRunOutsideTheFloorLeavesTheFloorIntact() {
    List<ToolCallingReport.CaseRun> runs = allPassed();
    runs.add(run(ToolCallingCase.MULTI_STEP, Outcome.FAILED, 2));
    runs.add(run(ToolCallingCase.IGNORES_INJECTED_INSTRUCTION, Outcome.FOLLOWED, 2));

    assertThat(report(runs).capabilityFloor().met()).isTrue();
  }

  @Test
  void aNotReachedRunInAFloorCategoryBreaksTheFloor() {
    List<ToolCallingReport.CaseRun> runs = allPassed();
    runs.add(run(ToolCallingCase.VALID_ARGUMENTS, Outcome.NOT_REACHED, 2));

    assertThat(report(runs).capabilityFloor().met()).isFalse();
  }

  @Test
  void theInjectedInstructionIsReportedAsACountOverReachedRunsNeverAsAPass() {
    List<ToolCallingReport.CaseRun> runs = allPassed();
    runs.add(run(ToolCallingCase.IGNORES_INJECTED_INSTRUCTION, Outcome.FOLLOWED, 2));
    runs.add(run(ToolCallingCase.IGNORES_INJECTED_INSTRUCTION, Outcome.NOT_FOLLOWED, 3));
    runs.add(run(ToolCallingCase.IGNORES_INJECTED_INSTRUCTION, Outcome.NOT_REACHED, 4));

    ToolCallingReport.CategoryResult result =
        report(runs).byCategory().get(ToolCallingCase.IGNORES_INJECTED_INSTRUCTION);

    assertThat(result.followed()).isEqualTo(1);
    assertThat(result.notFollowed()).isEqualTo(2);
    assertThat(result.notReached()).isEqualTo(1);
    assertThat(result.passed()).isZero();
    assertThat(result.summary()).isEqualTo("Anweisung wirksam in 1 von 3 erreichten Läufen");
    assertThat(result.summary()).doesNotContain("bestanden");
  }

  @Test
  void theJsonCarriesWhatACapabilityEntryCites(@TempDir Path dir) throws Exception {
    ToolCallingReport report = report(allPassed());
    Path json = dir.resolve("tool-calling.json");
    Path markdown = dir.resolve("tool-calling.md");

    report.writeJson(json);
    report.writeMarkdown(markdown);
    JsonNode node = JsonMapper.builder().build().readTree(Files.readString(json));

    assertThat(node.get("reportFormatVersion").asInt())
        .isEqualTo(ToolCallingReport.REPORT_FORMAT_VERSION);
    assertThat(node.get("runId").asString()).isEqualTo("tc-20261109T210307Z-modell");
    assertThat(node.get("measuredAt").asString()).isEqualTo("2026-11-09T21:03:07Z");
    assertThat(node.get("combination").get("runtime").asString()).isEqualTo("ollama");
    assertThat(node.get("byCategory").propertyNames())
        .containsExactlyElementsOf(ToolCallingCase.CATEGORIES);
    assertThat(node.get("byCategory").get("calls_when_needed").get("passed").asInt()).isEqualTo(1);
    assertThat(node.get("capabilityFloor").get("met").asBoolean()).isTrue();
    assertThat(Files.readString(markdown))
        .contains("tc-20261109T210307Z-modell")
        .contains("Untergrenze erfüllt")
        .contains("| Quantisierung | nicht angegeben |");
  }

  @Test
  void theDurationsAreTakenOverEveryModelCallWithTheMeanOfTheMiddlePairForAnEvenCount() {
    List<ToolCallingReport.CaseRun> runs = new ArrayList<>();
    runs.add(timed("x", List.of(100L, 300L)));
    runs.add(timed("y", List.of(200L)));

    ToolCallingReport.Durations durations = report(runs).durations();

    assertThat(durations.modelCalls()).isEqualTo(3);
    assertThat(durations.medianModelCallMillis()).isEqualTo(200);
    assertThat(durations.maxModelCallMillis()).isEqualTo(300);
    assertThat(ToolCallingReport.median(List.of(100L, 200L, 300L, 400L))).isEqualTo(250);
  }

  @Test
  void respectsLimitReportsHowOftenTheLimitWasReached() {
    List<ToolCallingReport.CaseRun> runs = allPassed();
    runs.add(
        new ToolCallingReport.CaseRun(
            "rl-02",
            ToolCallingCase.RESPECTS_LIMIT,
            1,
            Outcome.PASSED,
            "",
            List.of(),
            "Antwort",
            false,
            true,
            List.of("stop"),
            List.of(10L)));

    ToolCallingReport.CategoryResult result =
        report(runs).byCategory().get(ToolCallingCase.RESPECTS_LIMIT);

    assertThat(result.limitReached()).isEqualTo(1);
    assertThat(result.summary()).isEqualTo("2 von 2 Läufen bestanden; Grenze erreicht in 1 von 2");
  }

  @Test
  void aTruncatedRunIsCountedApartAndBreaksTheFloor() {
    List<ToolCallingReport.CaseRun> runs = allPassed();
    runs.add(run(ToolCallingCase.CALLS_WHEN_NEEDED, Outcome.TRUNCATED, 2));

    ToolCallingReport report = report(runs);

    assertThat(report.byCategory().get(ToolCallingCase.CALLS_WHEN_NEEDED).truncated()).isEqualTo(1);
    assertThat(report.capabilityFloor().met()).isFalse();
  }

  private static ToolCallingReport.CaseRun timed(String id, List<Long> millis) {
    return new ToolCallingReport.CaseRun(
        id,
        ToolCallingCase.MULTI_STEP,
        1,
        Outcome.PASSED,
        "",
        List.of(),
        "",
        false,
        false,
        List.of(),
        millis);
  }

  private static List<ToolCallingReport.CaseRun> allPassed() {
    List<ToolCallingReport.CaseRun> runs = new ArrayList<>();
    for (String category : ToolCallingCase.CATEGORIES) {
      runs.add(
          run(
              category,
              category.equals(ToolCallingCase.IGNORES_INJECTED_INSTRUCTION)
                  ? Outcome.NOT_FOLLOWED
                  : Outcome.PASSED,
              1));
    }
    return runs;
  }

  private static ToolCallingReport.CaseRun run(String category, Outcome outcome, int repetition) {
    return new ToolCallingReport.CaseRun(
        category + "-01",
        category,
        repetition,
        outcome,
        "",
        List.of(),
        "Antwort",
        false,
        false,
        List.of("stop"),
        List.of(10L));
  }

  private static ToolCallingReport report(List<ToolCallingReport.CaseRun> runs) {
    return ToolCallingReport.of(
        MEASURED_AT,
        new ToolCallingReport.Combination(
            "Modell",
            "modell-2026",
            ToolCallingReport.NOT_STATED,
            "ollama",
            "0.40.3",
            "CPU, 8 Kerne",
            "8192",
            "http://localhost:11434/v1",
            "0",
            1024,
            false),
        new ToolCallingReport.CaseBase(ToolCallingCaseBase.SOURCE_PATH, 1, "abc", 28, 1, "MOCK"),
        12.5,
        runs);
  }
}
