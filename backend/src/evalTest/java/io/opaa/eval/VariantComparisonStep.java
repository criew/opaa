package io.opaa.eval;

import io.opaa.indexing.IndexingProperties;
import io.opaa.query.QueryProperties;
import io.opaa.query.QueryService;
import io.opaa.query.QueryServiceDependencies;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.springframework.context.ApplicationContext;

/**
 * The opt-in Variantenvergleich step of a harness run (issue #1041,
 * docs/features/retrieval-benchmark.md §2), in one place instead of copied into each domain harness
 * — the same split {@link PipelineHarnessSupport} makes for the pipeline measurement path.
 *
 * <p>Runs at the very end of a harness run, on the corpus that run has already indexed and
 * manifest-verified: a variant costs a second pass of queries, never a second indexing run. Every
 * domain has its own default comparison file (issue #1049, which needed the step in all three
 * domains for the lexical path's Wirkungsnachweis); {@code -Dopaa.eval.variantComparisonFile}
 * points at any other one without a code change.
 */
public final class VariantComparisonStep {

  // System properties for the opt-in variant-comparison step (#1041) — see eval/variants/README.md.
  private static final String RUN_PROPERTY = "opaa.eval.runVariantComparison";
  private static final String FILE_PROPERTY = "opaa.eval.variantComparisonFile";

  private VariantComparisonStep() {}

  /** Whether this run was asked for a variant comparison at all. Off by default. */
  public static boolean isRequested() {
    return Boolean.getBoolean(RUN_PROPERTY);
  }

  /**
   * Resolves, loads and validates the opt-in comparison file (issue #1041 review, Befund 3) — to be
   * called once, early, before any indexing happens, and again from {@link #run} right before
   * actually running it. Re-loading is cheap (a small JSON file) and keeps the calling harness free
   * of state to thread through 400+ lines of its test method.
   */
  public static VariantComparison loadAndValidate(
      QueryProperties queryProperties, String defaultComparisonFile, String chatModel)
      throws IOException {
    Path repoRoot = RepoPaths.evalDir().getParent();
    Path comparisonFile =
        repoRoot.resolve(System.getProperty(FILE_PROPERTY, defaultComparisonFile));
    VariantComparison comparison = VariantComparisonDataset.load(comparisonFile);
    comparison.requireExecutableReference(queryProperties, chatModel != null);
    return comparison;
  }

  /**
   * Loads the declarative comparison, runs it, asserts the reference-variant self-check, and writes
   * the report.
   *
   * <p>Loading, running and writing are guarded exactly like the pipeline path ({@link
   * PipelineHarnessSupport#runAndWriteGuarded}, issue #1041 review, Befund 4): a {@link
   * RuntimeException} or {@link IOException} here must not fail the calling harness test, or the
   * baseline check that {@code dependsOn} it would lose the raw-vector path's already-completed
   * verdict to an observation the harness never promised. The Referenzvarianten-Selbstprüfung
   * assertions below stay hard on purpose: {@code assertThat(...).isEqualTo(...)} throws {@link
   * AssertionError}, not {@link RuntimeException}, so it is not caught by the guard and fails the
   * calling test as any other assertion would — the one failure mode this method must never
   * swallow, since it signals a bug in the variant mechanism itself, not a broken input.
   *
   * @param queryService the harness's own production-wired bean, the one the pipeline path measured
   *     with — the self-check compares against it, not against a second hand-built instance.
   */
  public static void run(
      EvalDomainConfig domain,
      String defaultComparisonFile,
      ApplicationContext applicationContext,
      PipelineHarnessSupport.RunIdentity identity,
      QueryService queryService,
      QueryProperties queryProperties,
      IndexingProperties indexingProperties,
      UUID evalLibraryId,
      List<GoldenCase> goldenCases,
      Logger log) {
    try {
      VariantComparison comparison =
          loadAndValidate(queryProperties, defaultComparisonFile, identity.chatModel());
      QueryServiceDependencies dependencies =
          QueryServiceDependencies.fromContext(applicationContext);

      VariantReport report =
          VariantComparisonRunner.run(
              comparison,
              dependencies,
              queryProperties,
              domain,
              identity,
              indexingProperties,
              evalLibraryId,
              goldenCases);

      // Referenzvarianten-Selbstprüfung (issue #1041 acceptance criteria): the reference variant
      // must reproduce, field for field, what the harness's own @Autowired QueryService bean
      // computes for the unchanged production configuration — the very bean the pipeline path
      // already measured with, not a second, hand-built instance (issue #1041 review, Befund 1: a
      // hand-built instance from QueryServiceDependencies would only prove the mechanism is
      // internally deterministic, not that it matches the production-wired pipeline). Both sides
      // follow the Mehrfachlauf-Regel — see ReferenceVariantSelfCheck.
      VariantOutcome referenceOutcome =
          report.outcomes().stream()
              .filter(o -> o.variant().name().equals(report.referenceVariant()))
              .findFirst()
              .orElseThrow();
      MehrfachlaufRule.Measurement direct =
          ReferenceVariantSelfCheck.assertMatchesDirectMeasurement(
              referenceOutcome,
              queryProperties.queryDecompositionEnabled(),
              () ->
                  PipelineHarnessSupport.measure(
                      domain,
                      identity,
                      queryService,
                      queryProperties,
                      indexingProperties,
                      evalLibraryId,
                      goldenCases,
                      Instant.now()));
      log.info(
          "Referenzvarianten-Selbstprüfung bestanden: bitgleiche Zahlen zum direkten Pipeline-Lauf"
              + " über das produktiv verdrahtete QueryService-Bean.{}",
          direct.multiRun() ? "\n" + MehrfachlaufRule.render(direct.summary()) : "");

      Path reportFile =
          Path.of("build", "eval-reports", "variant-report-" + comparison.name() + ".json");
      VariantReportWriter.writeJson(report, reportFile);
      log.info(VariantReportWriter.renderSummary(report));
      System.out.println("Variant report written to " + reportFile.toAbsolutePath());
    } catch (RuntimeException | IOException e) {
      log.error(
          "Variantenvergleich fehlgeschlagen, Rohvektor- und Pipeline-Pfad unberührt — deren "
              + "Messung und Baseline-Vergleich sind zu diesem Zeitpunkt bereits abgeschlossen "
              + "und von diesem Fehler nicht betroffen. Für diesen Lauf fehlt nur der "
              + "Variantenbericht.",
          e);
    }
  }
}
