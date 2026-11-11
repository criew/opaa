package io.opaa.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.agent.AgentDirectory.Responsibility;
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
}
