package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compares the most recent {@code evaluateVerwaltungRetrieval} report against the committed {@code
 * verwaltung} baseline (issue #1043) — the verwaltung counterpart of {@link
 * BaselineRegressionTest}. Own report file, own baseline file, own groups: the other two domains'
 * baselines and CI behaviour are unaffected by this class.
 *
 * <p>The per-class groups this compares ({@code category:literal_term_weak_embedding} and the four
 * others) are the reason the domain exists — a regression that only shows in the aggregate would
 * say nothing about the failure class a Retrieval-Baustein was built to close.
 */
class VerwaltungBaselineRegressionTest {

  private static final Logger log = LoggerFactory.getLogger(VerwaltungBaselineRegressionTest.class);

  private static final Path REPORT_FILE =
      Path.of("build", "eval-reports", "retrieval-metrics-verwaltung.json");
  private static final Path MARKDOWN_FILE =
      Path.of("build", "eval-reports", "baseline-comparison-verwaltung.md");

  @Test
  void currentRunStaysWithinToleranceOfTheCommittedBaseline() throws IOException {
    if (!Files.exists(REPORT_FILE)) {
      fail(
          "No report found at '"
              + REPORT_FILE.toAbsolutePath()
              + "'. Run './gradlew evaluateVerwaltungRetrieval' first — this test only compares an "
              + "existing report against the baseline, it does not produce one.");
    }

    EvaluationReport report =
        JsonMapper.builder()
            .build()
            .readValue(Files.readString(REPORT_FILE), EvaluationReport.class);
    BaselineComparator.requireBaselineComparable(report);
    Path baselineFile =
        RepoPaths.evalDir()
            .resolve("baseline")
            .resolve(EvalDomainConfig.VERWALTUNG.baselineFileName());
    Baseline baseline = Baseline.load(baselineFile);

    BaselineComparator.ComparisonResult result = BaselineComparator.compare(baseline, report);

    String markdown =
        BaselineMarkdownWriter.render(result, EvalDomainConfig.VERWALTUNG.baselineFileName());
    BaselineMarkdownWriter.write(
        result, MARKDOWN_FILE, EvalDomainConfig.VERWALTUNG.baselineFileName());
    log.info(markdown);
    System.out.println(markdown);
    System.out.println("Delta-Tabelle geschrieben nach " + MARKDOWN_FILE.toAbsolutePath());

    if (!result.baselineValid()) {
      fail(
          "Baseline ungültig, nicht vergleichbar mit dem aktuellen Lauf — die Messgrundlage hat "
              + "sich geändert, das ist keine Aussage über eine Retrieval-Regression: "
              + result.fixedPointMismatches()
              + ". Siehe eval/baseline/README.md für die bewusste Baseline-Aktualisierung.");
    }

    assertThat(result.chunkCountInvariantHolds())
        .as(
            "Chunk-Zahl-Invariante verletzt (ADR-0010) — harter Fehlschlag, kein Toleranzfall: %s",
            result.chunkCountInvariantViolations())
        .isTrue();

    assertThat(result.failedChecks())
        .as(
            "Retrieval-Regression gegenüber der Baseline erkannt:\n%s",
            result.failedChecks().stream().map(Object::toString).reduce("", (a, b) -> a + "\n" + b))
        .isEmpty();
  }
}
