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
 * Compares the most recent {@code evaluateRetrieval} report against the committed baseline (issue
 * #228). Deliberately Docker-free and separate from {@link RetrievalEvaluationHarnessTest}: it only
 * reads two JSON files and does not need Testcontainers, but it does need a report to have been
 * produced first — see {@code checkRetrievalBaseline} in {@code build.gradle.kts}, which runs this
 * test right after {@code evaluateRetrieval}.
 *
 * <p>Excluded from {@code evalUnitTest} (and therefore from {@code check}/{@code build}) for the
 * same reason {@link RetrievalEvaluationHarnessTest} is: it depends on a report file that only
 * exists after a real, multi-minute {@code evaluateRetrieval} run, so it must not run on every
 * regular build.
 */
class BaselineRegressionTest {

  private static final Logger log = LoggerFactory.getLogger(BaselineRegressionTest.class);

  private static final Path REPORT_FILE =
      Path.of("build", "eval-reports", "retrieval-metrics.json");
  private static final Path MARKDOWN_FILE =
      Path.of("build", "eval-reports", "baseline-comparison.md");

  @Test
  void currentRunStaysWithinToleranceOfTheCommittedBaseline() throws IOException {
    if (!Files.exists(REPORT_FILE)) {
      fail(
          "No report found at '"
              + REPORT_FILE.toAbsolutePath()
              + "'. Run './gradlew evaluateRetrieval' first — this test only compares an existing "
              + "report against the baseline, it does not produce one.");
    }

    EvaluationReport report =
        JsonMapper.builder()
            .build()
            .readValue(Files.readString(REPORT_FILE), EvaluationReport.class);
    Path baselineFile =
        RepoPaths.evalDir()
            .resolve("baseline")
            .resolve(EvalDomainConfig.COMIC_CHARACTERS.baselineFileName());
    Baseline baseline = Baseline.load(baselineFile);

    BaselineComparator.ComparisonResult result = BaselineComparator.compare(baseline, report);

    String markdown =
        BaselineMarkdownWriter.render(result, EvalDomainConfig.COMIC_CHARACTERS.baselineFileName());
    BaselineMarkdownWriter.write(
        result, MARKDOWN_FILE, EvalDomainConfig.COMIC_CHARACTERS.baselineFileName());
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
