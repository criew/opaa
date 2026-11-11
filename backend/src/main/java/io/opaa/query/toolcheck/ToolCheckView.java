package io.opaa.query.toolcheck;

/**
 * A tool check with what the web layer shows besides the row: the parsed report and whether the
 * model still has the measured address and identifier.
 *
 * @param report {@code null} until completed
 */
public record ToolCheckView(ToolCheck check, ToolCallingReport report, boolean currentCombination) {

  /** Longest text the capability field takes as measurement run. */
  static final int MAX_MEASUREMENT_RUN = 300;

  /**
   * The text to record as the measurement run of a capability statement: run, date, model, address
   * and case base; {@code null} until completed.
   */
  public String measurementRun() {
    if (report == null) {
      return null;
    }
    String text =
        report.runId()
            + ", "
            + report.measuredAt()
            + ", "
            + check.getModelIdentifier()
            + " an "
            + check.getBaseUrl()
            + ", Werkzeugprüfung Fassung "
            + report.caseBase().caseBaseVersion()
            + ", "
            + check.getRepetitions()
            + (check.getRepetitions() == 1 ? " Lauf" : " Läufe")
            + " je Fall";
    return text.length() > MAX_MEASUREMENT_RUN
        ? text.substring(0, MAX_MEASUREMENT_RUN - 1) + "…"
        : text;
  }
}
