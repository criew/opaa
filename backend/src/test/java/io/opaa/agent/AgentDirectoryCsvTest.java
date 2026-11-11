package io.opaa.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.agent.AgentDirectory.AgentEntry;
import io.opaa.agent.AgentDirectory.Responsibility;
import io.opaa.api.types.AgentPlanStep;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Cells, limits and the responsible unit as the CSV export writes them. */
class AgentDirectoryCsvTest {

  @Test
  void aCellIsQuotedAndAFormulaIsNeutralised() {
    assertThat(AgentDirectoryCsv.cell("Prüfung")).isEqualTo("\"Prüfung\"");
    assertThat(AgentDirectoryCsv.cell("Sag \"Hallo\"; bitte"))
        .isEqualTo("\"Sag \"\"Hallo\"\"; bitte\"");
    assertThat(AgentDirectoryCsv.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
    assertThat(AgentDirectoryCsv.cell("+1")).isEqualTo("\"'+1\"");
    assertThat(AgentDirectoryCsv.cell("@SUMME")).isEqualTo("\"'@SUMME\"");
    assertThat(AgentDirectoryCsv.cell("")).isEqualTo("\"\"");
  }

  @Test
  void limitsNameOnlyWhatTheAgentSetsItself() {
    assertThat(AgentDirectoryCsv.limits(AgentLimits.INSTALLATION))
        .isEqualTo("Grenzen der Installation");
    assertThat(AgentDirectoryCsv.limits(new AgentLimits(5, null, 60, null)))
        .isEqualTo("Werkzeugaufrufe: 5, Laufzeit: 60 Sekunden");
  }

  @Test
  void aMissingResponsibilityIsSaid() {
    assertThat(AgentDirectoryCsv.responsibility(new Responsibility("Referat 12", null, false)))
        .isEqualTo("Referat 12");
    assertThat(AgentDirectoryCsv.responsibility(new Responsibility(null, null, true)))
        .isEqualTo("fehlt");
    assertThat(AgentDirectoryCsv.responsibility(new Responsibility("Alt", null, true)))
        .isEqualTo("Alt (fehlt)");
  }

  @Test
  void anAssignmentWhoseLibrariesAreGoneIsNoneAndNeverAllReadable() {
    assertThat(AgentDirectoryCsv.libraries(agent(List.of(), true)))
        .isEqualTo("Alle Bibliotheken, die die fragende Person lesen darf");
    assertThat(AgentDirectoryCsv.libraries(agent(List.of(), false)))
        .isEqualTo("keine einsehbare Bibliothek (gelöscht oder nicht einsehbar)");
    assertThat(AgentDirectoryCsv.libraries(agent(List.of("A", "B"), false))).isEqualTo("A, B");
  }

  private static AgentEntry agent(List<String> libraryNames, boolean allReadableLibraries) {
    return new AgentEntry(
        UUID.randomUUID(),
        "Agent",
        null,
        "",
        libraryNames,
        allReadableLibraries,
        List.of(),
        AgentPlanStep.OFF,
        AgentLimits.INSTALLATION,
        List.of(),
        1,
        false,
        new Responsibility(null, null, true),
        null);
  }
}
