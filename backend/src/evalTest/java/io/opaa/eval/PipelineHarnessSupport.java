package io.opaa.eval;

import io.opaa.indexing.IndexingProperties;
import io.opaa.query.QueryProperties;
import io.opaa.query.QueryService;
import io.opaa.query.RetrievalPipelineProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

/**
 * The pipeline measurement path's harness half (issue #1039): everything the two domain harnesses
 * need to run their golden dataset through {@link
 * QueryService#retrieveRelevantChunksInGivenScopeWithDecomposition(String, List, Set,
 * io.opaa.indexing.metadata.MetadataFilter)} and write the resulting {@link
 * PipelineEvaluationReport}, in one place instead of copied into both near-duplicate harness
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
      String goldenDatasetSha256,
      // Issue #1049: a fact about the index this run measures, read from
      // FullTextIndexFillStateService after indexing — the lexical path cannot find a chunk that
      // is missing from the full-text index, and a run in which it silently contributed less
      // must not look like a full hybrid one.
      boolean fullTextIndexComplete,
      // Issue #1144: under which ingestion pipeline versions (all registered, not just the ones
      // this corpus routes through) this was measured — see IngestionPipelineFingerprint's
      // Javadoc.
      String ingestionPipelineFingerprint,
      // Issue #1085: the systemwide active chat model of this run (EvalChatModel.MODEL), or null
      // if there is none. Reported as a fixed point only for a configuration that actually
      // decomposes — see buildRunConfiguration — but carried here for every run, because the
      // prerequisite checks need to know whether decomposition could work at all.
      String chatModel) {}

  /**
   * Runs the pipeline measurement path and writes its report — <b>without ever failing the harness
   * run it is invoked from</b>.
   *
   * <p>This split is the point of the method, and it survived the pipeline path becoming a watchdog
   * of its own (issue #1040). It runs at the end of the same {@code @Test} method as the raw-vector
   * path, so a {@link RuntimeException} escaping it would fail {@code evaluateRetrieval}, which
   * means <b>neither</b> baseline test runs, which means the nightly job produces no verdict at all
   * on the raw-vector path and files an alert issue whose stock wording blames a regression that
   * was never measured. A failing pipeline path must never silence the raw-vector path's judgment.
   *
   * <p><b>The guard hides the failure from this run, not from the verdict.</b> {@code
   * PipelineBaselineRegressionTest} runs afterwards against the committed pipeline baseline and
   * fails on a missing report — so a pipeline failure is red exactly once, under the pipeline
   * path's own verdict, while the raw-vector path is judged normally in the same job.
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
      RetrievalPipelineProperties pipelineProperties,
      boolean rerankRoleUsable,
      IndexingProperties indexingProperties,
      UUID evalLibraryId,
      List<GoldenCase> goldenCases,
      Instant pipelineRunStart,
      Logger log) {
    requireMeasurableConfiguration(
        queryProperties, pipelineProperties, rerankRoleUsable, identity.chatModel());
    try {
      // Mehrfachlauf-Regel (issue #1044/#1085): with decomposition active this path is
      // nondeterministic and is measured three times; the median run is what the report — and
      // therefore the baseline comparison — is built from.
      // Each run reports its own duration; the first one starts at the phase start the caller
      // passed, so a single-run measurement is unchanged from before the rule applied here.
      AtomicReference<Instant> firstRunStart = new AtomicReference<>(pipelineRunStart);
      MehrfachlaufRule.Measurement measurement =
          MehrfachlaufRule.measure(
              queryProperties.queryDecompositionEnabled(),
              () ->
                  measure(
                      domain,
                      identity,
                      queryService,
                      queryProperties,
                      indexingProperties,
                      evalLibraryId,
                      goldenCases,
                      startOfNextRun(firstRunStart)));
      PipelineEvaluationReport report = measurement.report();
      PipelineReportWriter.writeJson(report, reportFile(domain));
      if (measurement.multiRun()) {
        // Both outputs, like every other summary this path writes: the logger reaches the test
        // report, System.out reaches Gradle's console via showStandardStreams.
        String multiRunSummary = MehrfachlaufRule.render(measurement.summary());
        log.info(multiRunSummary);
        System.out.println(multiRunSummary);
      }
      log.info(PipelineReportWriter.renderSummary(report));
      System.out.println("Pipeline report written to " + reportFile(domain).toAbsolutePath());
    } catch (RuntimeException | IOException e) {
      // IOException as well as RuntimeException: failing to *write* the observation artifact is no
      // more a reason to fail the run than failing to produce it.
      log.error(
          "Pipeline-Messpfad fehlgeschlagen, Rohvektor-Pfad unberührt — dessen Messung und "
              + "Baseline-Vergleich sind zu diesem Zeitpunkt bereits abgeschlossen und von diesem "
              + "Fehler nicht betroffen. Der fehlende Pipeline-Report ({}) lässt anschließend "
              + "PipelineBaselineRegressionTest fehlschlagen — der Fehler ist damit genau einmal "
              + "rot, unter dem Urteil des Pipeline-Pfads.",
          reportFile(domain),
          e);
    }
  }

  /**
   * Runs the pipeline measurement path and returns its report, without writing or logging it — the
   * half of {@link #runAndWriteGuarded} that a variant comparison (issue #1041,
   * docs/features/retrieval-benchmark.md §2) reuses for its reference variant's self-check: the
   * reference variant's own report (computed through {@link VariantRunner}, a second, independent
   * call into this same measurement) must equal, field for field, what this method computes for the
   * unmodified production configuration in the very same harness run.
   */
  public static PipelineEvaluationReport measure(
      EvalDomainConfig domain,
      RunIdentity identity,
      QueryService queryService,
      QueryProperties queryProperties,
      IndexingProperties indexingProperties,
      UUID evalLibraryId,
      List<GoldenCase> goldenCases,
      Instant pipelineRunStart) {
    Set<UUID> searchScope = Set.of(evalLibraryId);
    List<PipelineRetrievalEvaluator.CaseOutcome> outcomes =
        PipelineRetrievalEvaluator.evaluateAll(
            goldenCases,
            // No conversation history: a golden case is a standalone question, and the harness has
            // no chat to resolve a follow-up against. The case's filter (#1070) is carried in as
            // given, where the METADATA_FILTER stage applies it in both search paths.
            (query, metadataFilter) -> {
              QueryService.RetrievalWithDecomposition retrieval =
                  queryService.retrieveRelevantChunksInGivenScopeWithDecomposition(
                      query, List.of(), searchScope, metadataFilter);
              List<String> rankedFileNames =
                  retrieval.chunks().stream()
                      .map(chunk -> chunk.getMetadata().get("file_name"))
                      .map(value -> value == null ? null : value.toString())
                      .toList();
              return new PipelineRetrievalEvaluator.PipelineInvocationResult(
                  rankedFileNames, retrieval.searchQueries());
            });

    // Built after the run, not before: runDurationSeconds must cover the queries above.
    return PipelineRetrievalEvaluator.report(
        outcomes,
        buildRunConfiguration(
            domain,
            identity,
            queryProperties,
            indexingProperties,
            goldenCases.size(),
            searchScope.size(),
            pipelineRunStart));
  }

  /** The caller's phase start for the first run, the current instant for every later one. */
  private static Instant startOfNextRun(AtomicReference<Instant> firstRunStart) {
    Instant reserved = firstRunStart.getAndSet(null);
    return reserved != null ? reserved : Instant.now();
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
   *   <li><b>Query decomposition may only run with an active chat model</b> (issue #1085). Left on
   *       without one, {@code QueryDecompositionService#decompose} fails per query and falls back
   *       to single-query retrieval — a run that reports "with decomposition" while measuring
   *       without it, exactly the silent degradation the specification forbids. Which of the two
   *       settings a run measures is not prescribed here: it is a fixed point of every report and
   *       of every committed baseline, so a mismatch is caught as an incomparable baseline rather
   *       than as a regression.
   *   <li><b>No pipeline stage may be switched off.</b> This path measures the complete pipeline,
   *       and which stages it consists of is not part of a report's fixed points: a run with a
   *       stage switched off would carry a {@code runConfiguration} fingerprint identical to a full
   *       run and its numbers would then be booked against the committed baseline as a code
   *       regression or improvement. Making stage selection a measured dimension is a deliberate
   *       measurement-contract change (new fixed point, raised {@code
   *       PipelineEvaluationReport#PIPELINE_MEASUREMENT_CONTRACT_VERSION}, re-drawn baselines), not
   *       something a property may do silently.
   *   <li><b>Reranking must not run.</b> Same argument as for the lexical path, in the other
   *       direction: the committed baseline is the shipped configuration, and that one does not
   *       rerank. A rerank run belongs in the Variantenvergleich, whose candidate-window overrides
   *       are exactly what issue #1050 has to measure.
   *   <li><b>The lexical search path must be switched on</b> (issue #1049, the Auflage recorded in
   *       docs/features/hybrid-retrieval.md, Arbeitspaket 2). Since it feeds the fusion, {@code
   *       opaa.query.full-text-search-enabled} moves the selection; the committed pipeline baseline
   *       describes the shipped hybrid configuration, and a run that quietly measured the
   *       vector-only one would be judged against it as a code regression. The vector-only
   *       measurement is not forbidden — it is a named variant of the Variantenvergleich ({@link
   *       VariantRunner}, which measures deliberately labelled configurations and writes no
   *       baseline), and its value is a fixed point of every report either way.
   * </ul>
   */
  public static void requireMeasurableConfiguration(
      QueryProperties queryProperties,
      RetrievalPipelineProperties pipelineProperties,
      boolean rerankRoleUsable,
      String chatModel) {
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
    if (queryProperties.queryDecompositionEnabled() && chatModel == null) {
      throw new IllegalStateException(
          "opaa.query.query-decomposition-enabled is true, but this run has no systemwide active "
              + "chat model — decomposition would fail per query and silently fall back to "
              + "single-query retrieval, producing a run labelled 'with decomposition' that "
              + "measured without it. Install one (io.opaa.eval.EvalChatModel, issue #1085) or "
              + "measure the decomposition-off configuration.");
    }
    if (!pipelineProperties.disabledStages().isEmpty()) {
      throw new IllegalStateException(
          "opaa.query.pipeline.disabled-stages switches off "
              + pipelineProperties.disabledStages()
              + ", but this path measures the complete pipeline and no report field records which "
              + "stages ran. The run would be indistinguishable from a full one in its "
              + "runConfiguration and its numbers would be judged against the committed baseline "
              + "as a code change. Measure the full pipeline, or turn stage selection into a "
              + "measured dimension deliberately: new fixed point, raised "
              + "PIPELINE_MEASUREMENT_CONTRACT_VERSION, re-drawn baselines.");
    }
    if (!queryProperties.fullTextSearchEnabled()) {
      throw new IllegalStateException(
          "opaa.query.full-text-search-enabled is false, but the committed pipeline baseline "
              + "describes the shipped hybrid configuration, in which the lexical path feeds the "
              + "fusion (#1049). This run would measure the vector-only configuration and its "
              + "numbers would be judged against that baseline as a code change. Measure the "
              + "vector-only configuration as a named variant instead "
              + "(eval/variants/*-lexical-path.json, -Dopaa.eval.runVariantComparison=true).");
    }
    if (rerankRoleUsable && queryProperties.rerankCandidateCount() > 0) {
      throw new IllegalStateException(
          "the rerank model role is usable and opaa.query.rerank-candidate-count is "
              + queryProperties.rerankCandidateCount()
              + ", so this run would rerank - but the committed pipeline baseline describes the "
              + "shipped configuration, in which reranking is off (OPAA_RERANK_ENABLED). Its "
              + "numbers would be judged against that baseline as a code change. Measure "
              + "reranking as a named variant instead (eval/variants/*-reranking.json, "
              + "-Dopaa.eval.runVariantComparison=true), with the production configuration "
              + "left at rerank-candidate-count=0 for the run.");
    }
  }

  /**
   * Assembles a run's fixed-point configuration record. Public (issue #1041): {@link VariantRunner}
   * reuses this exact builder for every variant's report so a variant's {@code runConfiguration} is
   * built the identical way the single-configuration pipeline path builds its own, just with a
   * variant's {@link QueryProperties} instead of the production instance.
   */
  public static PipelineEvaluationReport.PipelineRunConfiguration buildRunConfiguration(
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
        queryProperties.fullTextSearchEnabled(),
        identity.fullTextIndexComplete(),
        queryProperties.queryDecompositionEnabled(),
        queryProperties.maxSubQueries(),
        // Only the configuration that actually decomposes reports a chat model: a run with
        // decomposition off never sends a single prompt, so naming a model there would claim a
        // fixed point that took no part in the measurement.
        queryProperties.queryDecompositionEnabled() ? identity.chatModel() : null,
        PipelineMetricsAggregate.HIT_RATE_K,
        PipelineMetricsAggregate.RANKING_K,
        identity.pgvectorIndexType(),
        identity.corpusManifestSha256(),
        identity.corpusDocumentCount(),
        identity.goldenDatasetFile(),
        identity.goldenDatasetSha256(),
        goldenCaseCount,
        identity.ingestionPipelineFingerprint(),
        // Every case's filter is carried into the run by measure() above - unconditionally, so
        // this is a statement about this code, not about a switch.
        true,
        searchScopeLibraryCount,
        SEARCH_SCOPE_NOTE,
        pipelineRunStart.toString(),
        Duration.between(pipelineRunStart, Instant.now()).toMillis() / 1000.0,
        EvalOllamaEndpoint.isExternal());
  }
}
