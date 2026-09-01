package io.opaa.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Renders a {@link BaselineComparator.ComparisonResult} as a Markdown delta table. The written file
 * ({@code backend/build/eval-reports/baseline-comparison.md}) is consumed twice by {@code
 * .github/workflows/retrieval-regression.yml}, not by this class directly: appended to {@code
 * $GITHUB_STEP_SUMMARY} on every run, and posted as a PR comment when the job is triggered via the
 * {@code evaluation} label (issue #228 acceptance criteria: "Ergebnis als PR-Kommentar mit
 * Delta-Tabelle gegenüber der Baseline").
 */
public final class BaselineMarkdownWriter {

  /**
   * Minimum delta before a metric is reported as an "improvement" hint. Deliberately non-zero: the
   * baseline stores metrics rounded to 3 decimals while a fresh report carries full {@code double}
   * precision, so an identical run already shows a tiny positive delta on essentially every metric
   * (e.g. {@code crosslingual}'s {@code hitRateAt5}: {@code 13/34 ≈ 0.38235} against a baseline of
   * {@code 0.382}) — without this threshold, the hint would fire on every single run, baseline
   * drift or not (PR #301 review).
   */
  private static final double IMPROVEMENT_HINT_THRESHOLD = 0.005;

  private BaselineMarkdownWriter() {}

  public static void write(
      BaselineComparator.ComparisonResult result,
      Path target,
      String baselineFileName,
      ExpectedStateAudit.Result expectedStateAudit)
      throws IOException {
    Files.createDirectories(target.getParent());
    Files.writeString(
        target, render(result, baselineFileName, expectedStateAudit), StandardCharsets.UTF_8);
  }

  /**
   * @param expectedStateAudit the run's declared-vs-measured case-state audit, appended below the
   *     delta table (issue #1043); {@code null} for a domain whose golden dataset declares no
   *     states, in which case the output is unchanged from before that issue.
   */
  public static String render(
      BaselineComparator.ComparisonResult result,
      String baselineFileName,
      ExpectedStateAudit.Result expectedStateAudit) {
    StringBuilder sb = new StringBuilder();
    // Issue #234: parameterized, not hardcoded to "comic-characters.json" — this class is shared
    // between both domains' *BaselineRegressionTest callers, and a hardcoded title here would
    // mislabel every city-landmarks delta table (PR comment, CI job summary) as belonging to the
    // comic-characters baseline while actually showing city-landmarks data.
    sb.append("## Retrieval-Regression gegen Baseline (`eval/baseline/")
        .append(baselineFileName)
        .append("`)\n\n");

    if (!result.baselineValid()) {
      sb.append(
          "**Baseline ungültig — kein Rückschluss auf Retrieval-Qualität möglich.** Die "
              + "Messgrundlage hat sich gegenüber der Baseline geändert (Korpus, Golden Dataset, "
              + "Embedding-Modell, Chunk-Größe oder Messvertrag). Das ist keine Aussage über eine "
              + "Retrieval-Regression — die Baseline muss bewusst neu gezogen werden (siehe "
              + "`eval/baseline/README.md`).\n\n");
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
      // Deliberately also on the invalid-baseline path: the states were still measured, and a
      // deviation is worth seeing even when no metric comparison is meaningful.
      sb.append(ExpectedStateAudit.renderMarkdown(expectedStateAudit));
      return sb.toString();
    }

    // In normal operation, RetrievalEvaluationHarnessTest itself asserts the Ein-Chunk-Invariante
    // and aborts *before* writing a report at all when it is violated (see its step 3) — so this
    // branch is unreachable via the harness's own report-writing path today. It is kept as a
    // second, defensive check (PR #301 review): it still fires correctly against a hand-edited or
    // otherwise externally produced report, and protects this class against silently becoming
    // wrong if the harness is ever changed to write partial reports on invariant failure.
    if (!result.chunkCountInvariantHolds()) {
      sb.append(
          "**Chunk-Zahl-Invariante verletzt (ADR-0010).** Das ist ein harter Fehlschlag, kein "
              + "Toleranzfall — die folgenden Dokumente verletzten die erwartete Chunk-Zahl:\n\n");
      sb.append("| Datei | Chunks |\n|---|---|\n");
      for (var violation : result.chunkCountInvariantViolations()) {
        sb.append(
            String.format(
                Locale.ROOT, "| `%s` | %d |\n", violation.fileName(), violation.chunkCount()));
      }
      sb.append('\n');
    }

    boolean anyFailed = !result.failedChecks().isEmpty();
    sb.append(anyFailed ? "**Regression erkannt.**\n\n" : "**Keine Regression.**\n\n");
    sb.append(
        "| Gruppe | Metrik | n | Baseline | Ist | Delta | Toleranz | Ergebnis |\n"
            + "|---|---|---|---|---|---|---|---|\n");
    boolean anyCaseBased = false;
    for (var check : result.checks()) {
      // Issue #306 (review Befund 1 — conjunction, not replacement): for a case-based pair,
      // tolerance() is already the *effective* mean tolerance (max(meanTolerance, 1/n)) actually
      // applied — the "*" plus the footnote below only flags that this pair *additionally*
      // required the case-count check to pass, not that the unit of tolerance() changed.
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
            "\n_Mindestens eine Metrik hat sich gegenüber der Baseline verbessert. Das lässt den "
                + "Job bestehen, ist aber ein Hinweis: Prüfen, ob die Baseline bewusst aktualisiert "
                + "werden sollte (siehe `eval/baseline/README.md`)._\n");
      }
    }

    sb.append(ExpectedStateAudit.renderMarkdown(expectedStateAudit));
    return sb.toString();
  }
}
