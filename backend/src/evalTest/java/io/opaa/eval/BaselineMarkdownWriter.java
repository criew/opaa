package io.opaa.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Renders a {@link BaselineComparator.ComparisonResult} as a Markdown delta table — used both for
 * the CI job summary and for the PR comment posted when the job is triggered via the {@code
 * evaluation} label (issue #228 acceptance criteria: "Ergebnis als PR-Kommentar mit Delta-Tabelle
 * gegenüber der Baseline").
 */
public final class BaselineMarkdownWriter {

  private BaselineMarkdownWriter() {}

  public static void write(BaselineComparator.ComparisonResult result, Path target)
      throws IOException {
    Files.createDirectories(target.getParent());
    Files.writeString(target, render(result), StandardCharsets.UTF_8);
  }

  public static String render(BaselineComparator.ComparisonResult result) {
    StringBuilder sb = new StringBuilder();
    sb.append("## Retrieval-Regression gegen Baseline (`eval/baseline/comic-characters.json`)\n\n");

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
      return sb.toString();
    }

    if (!result.oneChunkInvariantHolds()) {
      sb.append(
          "**Ein-Chunk-Invariante verletzt (ADR-0010).** Das ist ein harter Fehlschlag, kein "
              + "Toleranzfall — die folgenden Dokumente ergaben mehr als einen Chunk:\n\n");
      sb.append("| Datei | Chunks |\n|---|---|\n");
      for (var violation : result.oneChunkInvariantViolations()) {
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
    for (var check : result.checks()) {
      sb.append(
          String.format(
              Locale.ROOT,
              "| %s | %s | %d | %.3f | %.3f | %+.3f | %.3f | %s |\n",
              check.group(),
              check.metric(),
              check.n(),
              check.baselineValue(),
              check.currentValue(),
              check.delta(),
              check.tolerance(),
              check.passed() ? "✅" : "❌"));
    }

    if (!anyFailed) {
      boolean anyImprovement = result.checks().stream().anyMatch(c -> c.delta() > 0);
      if (anyImprovement) {
        sb.append(
            "\n_Mindestens eine Metrik hat sich gegenüber der Baseline verbessert. Das lässt den "
                + "Job bestehen, ist aber ein Hinweis: Prüfen, ob die Baseline bewusst aktualisiert "
                + "werden sollte (siehe `eval/baseline/README.md`)._\n");
      }
    }

    return sb.toString();
  }
}
