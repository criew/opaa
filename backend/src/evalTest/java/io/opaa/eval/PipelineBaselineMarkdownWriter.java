package io.opaa.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Renders a {@link PipelineBaselineComparator.ComparisonResult} as a Markdown delta table (issue
 * #1040) — the pipeline path's counterpart of {@link BaselineMarkdownWriter}, consumed by {@code
 * .github/workflows/retrieval-regression.yml} alongside, never instead of, the raw-vector path's
 * table.
 *
 * <p>Its own file and its own heading on purpose: the heading names the path and the window, so two
 * delta tables in the same job summary can never be read as one comparison across
 * non-interconvertible measurements (ADR-0012, Nachtrag, decision 12).
 */
public final class PipelineBaselineMarkdownWriter {

  /** Same rationale as {@link BaselineMarkdownWriter}'s constant of the same name. */
  private static final double IMPROVEMENT_HINT_THRESHOLD = 0.005;

  private PipelineBaselineMarkdownWriter() {}

  public static void write(
      PipelineBaselineComparator.ComparisonResult result,
      Path target,
      String baselineFileName,
      ExpectedStateAudit.Result expectedStateAudit)
      throws IOException {
    Files.createDirectories(target.getParent());
    Files.writeString(
        target, render(result, baselineFileName, expectedStateAudit), StandardCharsets.UTF_8);
  }

  /**
   * @param expectedStateAudit the run's declared-vs-measured case-state audit at this path's window
   *     (issue #1043); {@code null} for a domain whose golden dataset declares no states.
   */
  public static String render(
      PipelineBaselineComparator.ComparisonResult result,
      String baselineFileName,
      ExpectedStateAudit.Result expectedStateAudit) {
    StringBuilder sb = new StringBuilder();
    sb.append("## Pipeline-Messpfad gegen Baseline (`eval/baseline/")
        .append(baselineFileName)
        .append("`)\n\n");
    sb.append("_").append(PipelineMetricsAggregate.METRIC_WINDOW_NOTE).append("_\n\n");

    if (!result.baselineValid()) {
      sb.append(
          "**Pipeline-Baseline ungültig — kein Rückschluss auf Retrieval-Qualität möglich.** Die "
              + "Messgrundlage hat sich gegenüber der Baseline geändert (Korpus, Golden Dataset, "
              + "Embedding-Modell, Chunking, einer der Query-Parameter oder der Pipeline-"
              + "Messvertrag). Das ist keine Aussage über eine Regression — die Baseline muss "
              + "bewusst neu gezogen werden (siehe `eval/baseline/README.md`). Die Baseline des "
              + "Rohvektor-Pfads ist davon unberührt.\n\n");
      sb.append("| Feld | Baseline | Aktuell |\n|---|---|---|\n");
      for (var mismatch : result.fixedPointMismatches()) {
        sb.append(
            String.format(
                Locale.ROOT,
                "| `%s` | `%s` | `%s` |\n",
                mismatch.field(),
                mismatch.baselineValue(),
                mismatch.currentValue()));
      }
      // Same reasoning as in BaselineMarkdownWriter: the states were measured either way.
      sb.append(ExpectedStateAudit.renderMarkdown(expectedStateAudit));
      return sb.toString();
    }

    boolean anyFailed = !result.failedChecks().isEmpty();
    sb.append(
        anyFailed
            ? "**Regression im Pipeline-Messpfad erkannt.**\n\n"
            : "**Keine Regression im Pipeline-Messpfad.**\n\n");
    sb.append(
        "| Gruppe | Metrik | n | Baseline | Ist | Delta | Toleranz | Ergebnis |\n"
            + "|---|---|---|---|---|---|---|---|\n");
    boolean anyCaseBased = false;
    for (var check : result.checks()) {
      anyCaseBased = anyCaseBased || check.caseBasedCheck();
      sb.append(
          String.format(
              Locale.ROOT,
              "| %s | %s%s | %d | %.3f | %.3f | %+.3f | %.3f | %s |\n",
              check.group(),
              check.metric(),
              check.caseBasedCheck() ? "*" : "",
              check.n(),
              check.baselineValue(),
              check.currentValue(),
              check.delta(),
              check.tolerance(),
              check.passed() ? "✅" : "❌"));
    }
    if (anyCaseBased) {
      sb.append(
          "\n_* fallzahlbasierte Prüfung (issue #306): zusätzlich zur (auf mindestens eine "
              + "Fallbreite `1/n` geweiteten) Mittelwert-Toleranz in der Tabelle oben muss die "
              + "Zahl der Fälle mit einem Treffer gegenüber der Baseline um höchstens einen Fall "
              + "sinken — beide Bedingungen müssen gelten, siehe `BaselineComparator`s Javadoc._\n");
    }

    if (!anyFailed) {
      boolean anyImprovement =
          result.checks().stream().anyMatch(c -> c.delta() > IMPROVEMENT_HINT_THRESHOLD);
      if (anyImprovement) {
        sb.append(
            "\n_Mindestens eine Metrik hat sich gegenüber der Pipeline-Baseline verbessert. Das "
                + "lässt den Job bestehen, ist aber ein Hinweis: Prüfen, ob die Baseline bewusst "
                + "aktualisiert werden sollte (siehe `eval/baseline/README.md`)._\n");
      }
    }

    sb.append(ExpectedStateAudit.renderMarkdown(expectedStateAudit));
    return sb.toString();
  }
}
