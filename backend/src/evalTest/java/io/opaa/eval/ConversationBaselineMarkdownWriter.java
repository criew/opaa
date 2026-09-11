package io.opaa.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Renders a {@link ConversationBaselineComparator.ComparisonResult} together with its {@link
 * ConversationBaselineVerdict} as a Markdown delta table (issue #1553) — the multi-turn path's
 * counterpart of {@link PipelineBaselineMarkdownWriter}, consumed by {@code
 * .github/workflows/retrieval-regression.yml} alongside, never instead of, the other two paths'
 * tables.
 *
 * <p>Its own file and its own heading for the same reason the pipeline path has them: the heading
 * names the path and the window, so two delta tables in one job summary can never be read as one
 * comparison across non-interconvertible measurements (ADR-0012, Nachtrag, Entscheidung 12).
 */
public final class ConversationBaselineMarkdownWriter {

  /** Same rationale as {@link BaselineMarkdownWriter}'s constant of the same name. */
  private static final double IMPROVEMENT_HINT_THRESHOLD = 0.005;

  private ConversationBaselineMarkdownWriter() {}

  public static void write(
      ConversationBaselineComparator.ComparisonResult result,
      ConversationBaselineVerdict verdict,
      Path target,
      String baselineFileName,
      ExpectedStateAudit.Result expectedStateAudit)
      throws IOException {
    Files.createDirectories(target.getParent());
    Files.writeString(
        target,
        render(result, verdict, baselineFileName, expectedStateAudit),
        StandardCharsets.UTF_8);
  }

  public static String render(
      ConversationBaselineComparator.ComparisonResult result,
      ConversationBaselineVerdict verdict,
      String baselineFileName,
      ExpectedStateAudit.Result expectedStateAudit) {
    StringBuilder sb = new StringBuilder();
    sb.append("## Mehrrunden-Messpfad gegen Baseline (`eval/baseline/")
        .append(baselineFileName)
        .append("`)\n\n");
    sb.append("_").append(PipelineMetricsAggregate.METRIC_WINDOW_NOTE).append("_\n\n");
    sb.append(verdict.headline()).append("\n\n");
    if (!verdict.detail().isEmpty()) {
      sb.append(verdict.detail()).append("\n\n");
    }

    if (!result.baselineValid()) {
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
              + "Zahl der Runden mit einem Treffer gegenüber der Baseline um höchstens eine Runde "
              + "sinken — beide Bedingungen müssen gelten, siehe `BaselineComparator`s Javadoc._\n");
    }

    if (result.failedChecks().isEmpty()
        && result.checks().stream().anyMatch(c -> c.delta() > IMPROVEMENT_HINT_THRESHOLD)) {
      sb.append(
          "\n_Mindestens eine Metrik hat sich gegenüber der Mehrrunden-Baseline verbessert. Das "
              + "lässt den Job bestehen, ist aber ein Hinweis: Prüfen, ob die Baseline bewusst "
              + "aktualisiert werden sollte (siehe `eval/baseline/README.md`)._\n");
    }

    sb.append(ExpectedStateAudit.renderMarkdown(expectedStateAudit));
    return sb.toString();
  }
}
