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
 * Compares the most recent {@code evaluateCityLandmarksRetrieval} report against the committed
 * {@code city-landmarks} baseline (issue #234) — the city-landmarks counterpart of {@link
 * BaselineRegressionTest}. Own report file, own baseline file, own group (see {@link
 * EvalDomainConfig#CITY_LANDMARKS}): comic-characters' baseline and CI behavior are unaffected by
 * this class (issue #234 acceptance criterion "keine gemeinsame overall-Gruppe mit Comichelden").
 */
class CityLandmarksBaselineRegressionTest {

  private static final Logger log =
      LoggerFactory.getLogger(CityLandmarksBaselineRegressionTest.class);

  private static final Path REPORT_FILE =
      Path.of("build", "eval-reports", "retrieval-metrics-city-landmarks.json");
  private static final Path MARKDOWN_FILE =
      Path.of("build", "eval-reports", "baseline-comparison-city-landmarks.md");

  @Test
  void currentRunStaysWithinToleranceOfTheCommittedBaseline() throws IOException {
    if (!Files.exists(REPORT_FILE)) {
      fail(
          "No report found at '"
              + REPORT_FILE.toAbsolutePath()
              + "'. Run './gradlew evaluateCityLandmarksRetrieval' first — this test only compares "
              + "an existing report against the baseline, it does not produce one.");
    }

    EvaluationReport report =
        JsonMapper.builder()
            .build()
            .readValue(Files.readString(REPORT_FILE), EvaluationReport.class);
    Path baselineFile =
        RepoPaths.evalDir()
            .resolve("baseline")
            .resolve(EvalDomainConfig.CITY_LANDMARKS.baselineFileName());
    Baseline baseline = Baseline.load(baselineFile);

    BaselineComparator.ComparisonResult result = BaselineComparator.compare(baseline, report);

    String markdown = BaselineMarkdownWriter.render(result);
    BaselineMarkdownWriter.write(result, MARKDOWN_FILE);
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
