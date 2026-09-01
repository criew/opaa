package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import tools.jackson.databind.json.JsonMapper;

/**
 * The pipeline path's baseline verdict for one domain (issue #1040), shared by both domains' test
 * classes so the two stay one implementation rather than two copies that can drift.
 *
 * <p><b>Own verdict, own test class, own Gradle test — deliberately not folded into the raw-vector
 * path's baseline test.</b> The two paths are not interconvertible, so a single pass/fail bit over
 * both would say nothing about which measurement moved. Running them as two JUnit test classes in
 * the same Gradle task means JUnit produces both verdicts on every run: a red pipeline path never
 * suppresses the raw-vector path's judgment, and vice versa.
 *
 * <p><b>A missing pipeline report is a failure here, by design.</b> {@link
 * PipelineHarnessSupport#runAndWriteGuarded} still swallows a pipeline failure so the harness run
 * itself stays green — that guard exists to protect the raw-vector verdict, not to excuse a missing
 * measurement. With a committed baseline, "the pipeline path produced nothing" is exactly as much a
 * finding as "it produced worse numbers", and it surfaces here instead of only in a log line.
 */
final class PipelineBaselineRegressionCheck {

  private PipelineBaselineRegressionCheck() {}

  static void run(EvalDomainConfig domain, Logger log) throws IOException {
    Path reportFile = PipelineHarnessSupport.reportFile(domain);
    Path markdownFile =
        Path.of("build", "eval-reports", "pipeline-baseline-comparison-" + domain.name() + ".md");

    if (!Files.exists(reportFile)) {
      fail(
          "No pipeline report found at '"
              + reportFile.toAbsolutePath()
              + "'. Either the evaluation run has not happened yet (run the domain's "
              + "'evaluate…Retrieval' task first — this test only compares an existing report "
              + "against the baseline), or the pipeline measurement path failed during that run "
              + "and only logged it (see PipelineHarnessSupport#runAndWriteGuarded). The "
              + "raw-vector path's verdict is unaffected either way; look for the "
              + "'Pipeline-Messpfad fehlgeschlagen' log entry of the run for the cause.");
    }

    PipelineEvaluationReport report =
        JsonMapper.builder()
            .build()
            .readValue(Files.readString(reportFile), PipelineEvaluationReport.class);
    Path baselineFile =
        RepoPaths.evalDir().resolve("baseline").resolve(domain.pipelineBaselineFileName());
    PipelineBaselineComparator.requireBaselineComparable(report);

    PipelineBaseline baseline = PipelineBaseline.load(baselineFile);

    PipelineBaselineComparator.ComparisonResult result =
        PipelineBaselineComparator.compare(baseline, report);

    String markdown =
        PipelineBaselineMarkdownWriter.render(
            result, domain.pipelineBaselineFileName(), report.expectedStateAudit());
    PipelineBaselineMarkdownWriter.write(
        result, markdownFile, domain.pipelineBaselineFileName(), report.expectedStateAudit());
    // Both outputs on purpose, matching BaselineRegressionTest exactly: the logger reaches the test
    // report, System.out reaches Gradle's console via showStandardStreams. Dropping either would
    // make the two paths' output differ in where a delta table can be found.
    log.info(markdown);
    System.out.println(markdown);
    System.out.println("Delta-Tabelle geschrieben nach " + markdownFile.toAbsolutePath());

    if (!result.baselineValid()) {
      fail(
          "Pipeline-Baseline ungültig, nicht vergleichbar mit dem aktuellen Lauf — die "
              + "Messgrundlage des Pipeline-Pfads hat sich geändert, das ist keine Aussage über "
              + "eine Retrieval-Regression: "
              + result.fixedPointMismatches()
              + ". Siehe eval/baseline/README.md für die bewusste Baseline-Aktualisierung.");
    }

    assertThat(result.failedChecks())
        .as(
            "Regression im Pipeline-Messpfad gegenüber der Baseline erkannt (Fenster: "
                + "Hit Rate@5, MRR@8, nDCG@8, Recall@8 — nicht mit dem Rohvektor-Pfad "
                + "vergleichbar):\n%s",
            result.failedChecks().stream().map(Object::toString).reduce("", (a, b) -> a + "\n" + b))
        .isEmpty();
  }
}
