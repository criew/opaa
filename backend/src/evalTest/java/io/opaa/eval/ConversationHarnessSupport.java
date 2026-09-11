package io.opaa.eval;

import io.opaa.eval.ConversationEvaluationReport.ConversationRunConfiguration;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.query.QueryProperties;
import io.opaa.query.RetrievalContextFactory;
import io.opaa.query.retrieval.RetrievalPipeline;
import io.opaa.query.retrieval.RetrievalPipelineResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * The opt-in multi-turn step of a harness run (issue #1484, docs/features/conversation-memory.md,
 * "Messung"), in one place instead of copied into each domain harness - the same split {@link
 * PipelineHarnessSupport} makes for the single-question pipeline path.
 *
 * <p>Runs at the very end of a harness run, on the corpus that run has already indexed and
 * manifest-verified: the multi-turn cases cost a second pass of queries, never a second indexing
 * run, and they therefore measure provably the same index.
 *
 * <p><b>Manually invoked, never nightly.</b> Every turn costs a decomposition call, and under the
 * Mehrfachlauf-Regel the whole dataset is measured three times - far more than the nightly job's
 * budget carries. Switch it on with {@code -Dopaa.eval.runConversations=true}, together with {@code
 * -Dopaa.eval.queryDecomposition=true}; without the latter the run reports itself as not executed
 * rather than measuring the fallback (see {@link ConversationRunPrerequisites}).
 */
public final class ConversationHarnessSupport {

  private static final String RUN_PROPERTY = "opaa.eval.runConversations";

  private ConversationHarnessSupport() {}

  /** Whether this run was asked for a multi-turn measurement at all. Off by default. */
  public static boolean isRequested() {
    return Boolean.getBoolean(RUN_PROPERTY);
  }

  /** Where a domain's multi-turn report is written. */
  public static Path reportFile(EvalDomainConfig domain) {
    return Path.of("build", "eval-reports", "pipeline-conversations-" + domain.name() + ".json");
  }

  /** Where its Markdown rendering is written. */
  public static Path markdownFile(EvalDomainConfig domain) {
    return Path.of("build", "eval-reports", "pipeline-conversations-" + domain.name() + ".md");
  }

  /**
   * Runs the multi-turn measurement path and writes its report - <b>without ever failing the
   * harness run it is invoked from</b>, exactly like {@link
   * PipelineHarnessSupport#runAndWriteGuarded} and for the identical reason: this step runs at the
   * end of the same {@code @Test} method as the raw-vector and pipeline paths, whose measurements
   * and baseline verdicts are already complete at this point and must not be lost to a failure of
   * an observation that was added afterwards.
   *
   * @param identity the calling harness's run identity; its golden-dataset fields are replaced here
   *     by the multi-turn dataset's, since that is the dataset this run measured.
   * @param chatMemory the production conversation memory bean - the window every turn receives is
   *     built by it, not by the harness.
   */
  public static void runAndWriteGuarded(
      EvalDomainConfig domain,
      PipelineHarnessSupport.RunIdentity identity,
      RetrievalPipeline pipeline,
      RetrievalContextFactory contextFactory,
      ChatMemory chatMemory,
      IndexingProperties indexingProperties,
      UUID evalLibraryId,
      Logger log) {
    try {
      QueryProperties queryProperties = contextFactory.queryProperties();
      List<ConversationCase> cases = ConversationDataset.load(ConversationDataset.file(domain));
      Optional<String> notExecuted =
          ConversationRunPrerequisites.notExecutedReason(
              queryProperties, identity.chatModel(), cases.size());
      if (notExecuted.isPresent()) {
        String message =
            "Mehrrunden-Messpfad NICHT AUSGEFÜHRT: "
                + notExecuted.get()
                + " Es wurde nichts gemessen und nichts geschrieben — dieser Lauf trifft keine "
                + "Aussage über das Gesprächsgedächtnis.";
        log.warn(message);
        System.out.println(message);
        return;
      }

      ConversationMemoryProfile memoryProfile = ConversationMemoryProfile.measuredFrom(chatMemory);
      // Mehrfachlauf-Regel (docs/features/retrieval-benchmark.md §3): this path always decomposes
      // and is therefore never deterministic - three runs, the median one is what the report and
      // any baseline comparison are built from.
      List<ConversationEvaluationReport> runs =
          new ArrayList<>(MultiRunAggregator.DECOMPOSITION_RUN_COUNT);
      for (int i = 0; i < MultiRunAggregator.DECOMPOSITION_RUN_COUNT; i++) {
        runs.add(
            measure(
                domain,
                identity,
                pipeline,
                contextFactory,
                chatMemory,
                memoryProfile,
                indexingProperties,
                evalLibraryId,
                cases,
                Instant.now()));
      }
      ConversationEvaluationReport report = medianRun(runs);

      ConversationReportWriter.writeJson(report, reportFile(domain));
      ConversationReportWriter.writeMarkdown(report, markdownFile(domain));
      String summary = ConversationReportWriter.renderSummary(report);
      log.info(summary);
      System.out.println(summary);
      System.out.println(
          "Mehrrunden-Report geschrieben nach " + reportFile(domain).toAbsolutePath());
    } catch (RuntimeException | IOException e) {
      log.error(
          "Mehrrunden-Messpfad fehlgeschlagen, Rohvektor- und Pipeline-Pfad unberührt — deren "
              + "Messung und Baseline-Vergleich sind zu diesem Zeitpunkt bereits abgeschlossen "
              + "und von diesem Fehler nicht betroffen. Für diesen Lauf fehlt nur der "
              + "Mehrrunden-Bericht ({}).",
          reportFile(domain),
          e);
    }
  }

  /**
   * One measurement of the whole multi-turn dataset: every case, turn by turn, through the same
   * {@link RetrievalContextFactory}/{@link RetrievalPipeline} pair a chat query uses.
   *
   * <p>The turn's conversation window is handed in as the context's conversation history - the one
   * place this path differs from the single-question one, which passes an empty list. The
   * Gesprächsnotiz travels alongside it and is empty until it exists.
   */
  public static ConversationEvaluationReport measure(
      EvalDomainConfig domain,
      PipelineHarnessSupport.RunIdentity identity,
      RetrievalPipeline pipeline,
      RetrievalContextFactory contextFactory,
      ChatMemory chatMemory,
      ConversationMemoryProfile memoryProfile,
      IndexingProperties indexingProperties,
      UUID evalLibraryId,
      List<ConversationCase> cases,
      Instant runStart)
      throws IOException {
    Set<UUID> searchScope = Set.of(evalLibraryId);
    List<ConversationRetrievalEvaluator.CaseOutcome> outcomes =
        ConversationRetrievalEvaluator.evaluateAll(
            cases,
            chatMemory,
            (conversationCase, turnIndex, conversationWindow, conversationNote) -> {
              RetrievalPipelineResult result =
                  pipeline.run(
                      contextFactory.contextFor(
                          conversationCase.turns().get(turnIndex).query(),
                          conversationWindow,
                          searchScope,
                          MetadataFilter.NONE));
              List<String> rankedFileNames =
                  result.chunks().stream()
                      .map(chunk -> chunk.getMetadata().get("file_name"))
                      .map(value -> value == null ? null : value.toString())
                      .toList();
              return new ConversationRetrievalEvaluator.TurnInvocationResult(
                  rankedFileNames, result.searchQueries());
            });

    return ConversationRetrievalEvaluator.report(
        outcomes,
        runConfiguration(
            domain,
            identity,
            contextFactory.queryProperties(),
            indexingProperties,
            memoryProfile,
            cases,
            searchScope.size(),
            runStart));
  }

  /**
   * The run's fixed points: the pipeline path's own builder, fed with the multi-turn dataset's file
   * name, hash and case count, plus the conversation-memory profile.
   *
   * <p>{@code metadataFilterEnabled} is {@code false} here and not a shortcut: multi-turn cases
   * carry no core-field filter - what they measure is the resolution of a reference, and a filter
   * would decide the ranking before the resolution could.
   */
  private static ConversationRunConfiguration runConfiguration(
      EvalDomainConfig domain,
      PipelineHarnessSupport.RunIdentity identity,
      QueryProperties queryProperties,
      IndexingProperties indexingProperties,
      ConversationMemoryProfile memoryProfile,
      List<ConversationCase> cases,
      int searchScopeLibraryCount,
      Instant runStart)
      throws IOException {
    Path datasetFile = ConversationDataset.file(domain);
    PipelineHarnessSupport.RunIdentity conversationIdentity =
        new PipelineHarnessSupport.RunIdentity(
            identity.embeddingProvider(),
            identity.embeddingModel(),
            identity.embeddingModelDigest(),
            identity.ollamaImage(),
            identity.embeddingDimensions(),
            identity.chunkSizeMatchesApplicationDefault(),
            identity.pgvectorIndexType(),
            identity.corpusManifestSha256(),
            identity.corpusDocumentCount(),
            "eval/golden/" + domain.conversationDatasetFileName(),
            ConversationDataset.sha256(datasetFile),
            identity.fullTextIndexComplete(),
            identity.ingestionPipelineFingerprint(),
            identity.chatModel());
    return new ConversationRunConfiguration(
        PipelineHarnessSupport.buildRunConfiguration(
            domain,
            conversationIdentity,
            queryProperties,
            indexingProperties,
            cases.size(),
            searchScopeLibraryCount,
            false,
            runStart),
        memoryProfile,
        ConversationDataset.turnCount(cases));
  }

  /**
   * The median of the repeated runs by overall nDCG@8 - the finest-grained of the four metrics, the
   * same tie-break {@link MultiRunAggregator} applies to the single-question path's repeated runs.
   */
  static ConversationEvaluationReport medianRun(List<ConversationEvaluationReport> runs) {
    if (runs.isEmpty()) {
      throw new IllegalArgumentException("medianRun needs at least one run");
    }
    List<ConversationEvaluationReport> sorted = new ArrayList<>(runs);
    sorted.sort(Comparator.comparingDouble(run -> run.overall().ndcgAt8()));
    return sorted.get(sorted.size() / 2);
  }
}
