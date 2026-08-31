package io.opaa.eval;

import io.opaa.indexing.IndexingProperties;
import io.opaa.query.QueryProperties;
import io.opaa.query.QueryService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;

/**
 * The pipeline measurement path's harness half (issue #1039): everything the two domain harnesses
 * need to run their golden dataset through {@link
 * QueryService#retrieveRelevantChunksInGivenScope(String, List, Set)} and write the resulting
 * {@link PipelineEvaluationReport}, in one place instead of copied into both near-duplicate harness
 * classes.
 *
 * <p>Runs on the corpus the calling harness has already indexed and manifest-verified — the
 * pipeline path costs a second pass of queries, never a second indexing run, and therefore measures
 * against provably the same index the raw-vector path just measured.
 *
 * <p><b>What is measured</b> (docs/features/retrieval-benchmark.md §1): steps 2 to 6 of
 * docs/features/retrieval-algorithm.md — decomposition, one vector search per sub-question, MMR,
 * Reciprocal Rank Fusion, document completion. Step 7 (answer generation, citation validation,
 * source mapping) is out of scope: it is not retrieval and is not expressible in ranking metrics.
 * Step 1 (scope determination) is out of scope too — see {@link #SEARCH_SCOPE_NOTE}.
 *
 * <p><b>Production configuration, thresholds included.</b> Every parameter comes from the running
 * {@link QueryProperties}; unlike the raw-vector path (ADR-0012 decision 3), the similarity
 * threshold is actually applied here, not merely reported. A chunk below it never enters the
 * selection, so a document can disappear from the ranking entirely rather than merely fall back —
 * which is why this path's Recall lies systematically lower and why its numbers must never be
 * placed next to the raw path's without their window.
 */
public final class PipelineHarnessSupport {

  /**
   * Recorded in every report: the harness searches one fixed library that holds the whole corpus,
   * because permission enforcement is covered by the backend's integration tests and measuring it
   * here would shift the metrics by a factor that has nothing to do with retrieval quality.
   */
  public static final String SEARCH_SCOPE_NOTE =
      "Fester, vollständiger Suchbereich: genau die eine Eval-Bibliothek, die den gesamten "
          + "verifizierten Korpus enthält. Die Rechtedurchsetzung aus Schritt 1 der Pipeline ist "
          + "ausdrücklich nicht Messgegenstand (docs/features/retrieval-benchmark.md, Abschnitt 1).";

  private static final String SIMILARITY_THRESHOLD_NOTE =
      "Die Produktionsschwelle wurde in jeder Suche dieses Laufs tatsächlich angewandt, nicht nur "
          + "ausgewiesen — anders als im Rohvektor-Pfad (ADR-0012, Entscheidung 3). Ein Dokument "
          + "kann dadurch ganz aus der Rangliste verschwinden statt nur zurückzufallen; die "
          + "Recall-Werte liegen systematisch niedriger als dort.";

  private PipelineHarnessSupport() {}

  /**
   * The run's identity fields, all of which the calling harness already knows — kept as one record
   * so the method below has a readable signature rather than two dozen positional arguments.
   */
  public record RunIdentity(
      String embeddingProvider,
      String embeddingModel,
      String embeddingModelDigest,
      String ollamaImage,
      int embeddingDimensions,
      boolean chunkSizeMatchesApplicationDefault,
      String pgvectorIndexType,
      String corpusManifestSha256,
      int corpusDocumentCount,
      String goldenDatasetFile,
      String goldenDatasetSha256) {}

  /**
   * Runs the pipeline measurement path and writes its report — <b>without ever failing the harness
   * run it is invoked from</b>.
   *
   * <p>This split is the point of the method. The pipeline path is an observation artifact, not a
   * watchdog: it has no baseline and no verdict. It runs at the end of the same {@code @Test}
   * method as the raw-vector path, so a {@link RuntimeException} escaping it would fail {@code
   * evaluateRetrieval}, which means {@code BaselineRegressionTest} never runs, which means the
   * nightly job produces <b>no verdict at all on the raw-vector path</b> and files an alert issue
   * whose stock wording blames a regression that was never measured. A failing observation must
   * never silence the watchdog.
   *
   * <p>{@link #requireMeasurableConfiguration} is the deliberate exception and runs <b>before</b>
   * the guarded section: a configuration under which the reported numbers would not mean what their
   * names say is a setup error the run must not paper over, and it is decided before any
   * measurement happens. Callers are expected to have invoked it at the very start of their run
   * already (see its own Javadoc); the call here is the second line of defence, not the first.
   *
   * <p>{@code pipelineRunStart} is the start of this measurement phase, not of the whole harness
   * run — the reported duration is the cost of the queries alone, since indexing was already paid
   * for by the raw-vector path.
   */
  public static void runAndWriteGuarded(
      EvalDomainConfig domain,
      RunIdentity identity,
      QueryService queryService,
      QueryProperties queryProperties,
      IndexingProperties indexingProperties,
      UUID evalLibraryId,
      List<GoldenCase> goldenCases,
      Instant pipelineRunStart,
      Logger log) {
    requireMeasurableConfiguration(queryProperties);
    try {
      PipelineEvaluationReport report =
          runAndWrite(
              domain,
              identity,
              queryService,
              queryProperties,
              indexingProperties,
              evalLibraryId,
              goldenCases,
              pipelineRunStart);
      log.info(PipelineReportWriter.renderSummary(report));
      System.out.println("Pipeline report written to " + reportFile(domain).toAbsolutePath());
    } catch (RuntimeException | IOException e) {
      // IOException as well as RuntimeException: failing to *write* the observation artifact is no
      // more a reason to fail the run than failing to produce it.
      log.error(
          "Pipeline-Messpfad fehlgeschlagen, Rohvektor-Pfad unberührt — dessen Messung und "
              + "Baseline-Vergleich sind zu diesem Zeitpunkt bereits abgeschlossen und von diesem "
              + "Fehler nicht betroffen. Für diesen Lauf fehlt nur der Pipeline-Report ({}).",
          reportFile(domain),
          e);
    }
  }

  private static PipelineEvaluationReport runAndWrite(
      EvalDomainConfig domain,
      RunIdentity identity,
      QueryService queryService,
      QueryProperties queryProperties,
      IndexingProperties indexingProperties,
      UUID evalLibraryId,
      List<GoldenCase> goldenCases,
      Instant pipelineRunStart)
      throws IOException {
    Set<UUID> searchScope = Set.of(evalLibraryId);
    List<PipelineRetrievalEvaluator.CaseOutcome> outcomes =
        PipelineRetrievalEvaluator.evaluateAll(
            goldenCases,
            // No conversation history: a golden case is a standalone question, and the harness has
            // no chat to resolve a follow-up against.
            query ->
                queryService
                    .retrieveRelevantChunksInGivenScope(query, List.of(), searchScope)
                    .stream()
                    .map(chunk -> chunk.getMetadata().get("file_name"))
                    .map(value -> value == null ? null : value.toString())
                    .toList());

    // Built after the run, not before: runDurationSeconds must cover the queries above.
    PipelineEvaluationReport report =
        PipelineRetrievalEvaluator.report(
            outcomes,
            runConfiguration(
                domain,
                identity,
                queryProperties,
                indexingProperties,
                goldenCases.size(),
                searchScope.size(),
                pipelineRunStart));

    PipelineReportWriter.writeJson(report, reportFile(domain));
    return report;
  }

  /** Where a domain's pipeline report is written — never the raw-vector path's report file. */
  public static Path reportFile(EvalDomainConfig domain) {
    return Path.of("build", "eval-reports", "pipeline-metrics-" + domain.name() + ".json");
  }

  /**
   * Refuses to measure a configuration whose numbers would not mean what the report says they mean.
   *
   * <p><b>Call this at the very start of a harness run, not only through {@link
   * #runAndWriteGuarded}.</b> It reads nothing but {@link QueryProperties}, which is fixed from
   * context startup, so it can decide immediately — whereas the pipeline path itself runs after the
   * raw-vector path, which takes tens of minutes to hours. Failing only there would turn a
   * configuration mistake into a red job that never got to hear {@code BaselineRegressionTest}'s
   * verdict, the very outcome {@link #runAndWriteGuarded} exists to prevent. Idempotent and free:
   * calling it twice costs nothing.
   *
   * <ul>
   *   <li><b>{@code top-k} must equal {@link PipelineMetricsAggregate#RANKING_K}</b>, because the
   *       report's metric names state that window literally. A changed production default is a
   *       deliberate measurement-contract change (new window, new names, new pipeline contract
   *       version), not something to absorb silently.
   *   <li><b>Query decomposition must be switched off</b> for now. Left on, the harness's context
   *       has no active chat model, {@code QueryDecompositionService#decompose} would fail per
   *       query and fall back to single-query retrieval — a run that reports "with decomposition"
   *       while measuring without it. The specification forbids exactly that silent degradation,
   *       and which chat model the pipeline path should use is still an open decision
   *       (docs/features/retrieval-benchmark.md, "Offene Punkte" 3).
   * </ul>
   */
  public static void requireMeasurableConfiguration(QueryProperties queryProperties) {
    if (queryProperties.topK() != PipelineMetricsAggregate.RANKING_K) {
      throw new IllegalStateException(
          "opaa.query.top-k is "
              + queryProperties.topK()
              + ", but the pipeline path reports its metrics as MRR@"
              + PipelineMetricsAggregate.RANKING_K
              + "/nDCG@"
              + PipelineMetricsAggregate.RANKING_K
              + "/Recall@"
              + PipelineMetricsAggregate.RANKING_K
              + ". Changing the production top-k changes what every one of those numbers means: "
              + "update PipelineMetricsAggregate's window constant and component names, raise "
              + "PipelineEvaluationReport.PIPELINE_MEASUREMENT_CONTRACT_VERSION, and re-measure "
              + "deliberately.");
    }
    if (queryProperties.queryDecompositionEnabled()) {
      throw new IllegalStateException(
          "opaa.query.query-decomposition-enabled is true, but this harness's context has no "
              + "active chat model — decomposition would fail per query and silently fall back to "
              + "single-query retrieval, producing a run labelled 'with decomposition' that "
              + "measured without it. Set the property to false for the run (the decomposition-off "
              + "variant), or supply a chat model once that open decision is settled "
              + "(docs/features/retrieval-benchmark.md, 'Offene Punkte' 3).");
    }
  }

  private static PipelineEvaluationReport.PipelineRunConfiguration runConfiguration(
      EvalDomainConfig domain,
      RunIdentity identity,
      QueryProperties queryProperties,
      IndexingProperties indexingProperties,
      int goldenCaseCount,
      int searchScopeLibraryCount,
      Instant pipelineRunStart) {
    return new PipelineEvaluationReport.PipelineRunConfiguration(
        domain.name(),
        identity.embeddingProvider(),
        identity.embeddingModel(),
        identity.embeddingModelDigest(),
        identity.ollamaImage(),
        identity.embeddingDimensions(),
        indexingProperties.chunkSize(),
        identity.chunkSizeMatchesApplicationDefault(),
        indexingProperties.chunkOverlap(),
        queryProperties.fetchK(),
        queryProperties.topK(),
        queryProperties.similarityThreshold(),
        SIMILARITY_THRESHOLD_NOTE,
        queryProperties.maxChunksPerDocument(),
        queryProperties.mmrLambda(),
        queryProperties.queryDecompositionEnabled(),
        queryProperties.maxSubQueries(),
        // Null rather than a placeholder string: no chat model took part in this run at all, and
        // requireMeasurableConfiguration above guarantees none silently could.
        null,
        PipelineMetricsAggregate.HIT_RATE_K,
        PipelineMetricsAggregate.RANKING_K,
        identity.pgvectorIndexType(),
        identity.corpusManifestSha256(),
        identity.corpusDocumentCount(),
        identity.goldenDatasetFile(),
        identity.goldenDatasetSha256(),
        goldenCaseCount,
        searchScopeLibraryCount,
        SEARCH_SCOPE_NOTE,
        pipelineRunStart.toString(),
        Duration.between(pipelineRunStart, Instant.now()).toMillis() / 1000.0,
        EvalOllamaEndpoint.isExternal());
  }
}
