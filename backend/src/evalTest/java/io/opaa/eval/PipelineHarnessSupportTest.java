package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.query.QueryProperties;
import io.opaa.query.QueryService;
import io.opaa.query.RetrievalPipelineProperties;
import io.opaa.query.RetrievalStageName;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Docker-free unit tests for the two guarantees {@link PipelineHarnessSupport#runAndWriteGuarded}
 * makes about failures (issue #1039), run via the {@code evalUnitTest} Gradle task.
 */
class PipelineHarnessSupportTest {

  private static final PipelineHarnessSupport.RunIdentity IDENTITY =
      new PipelineHarnessSupport.RunIdentity(
          "ollama",
          "model",
          "digest",
          "image",
          768,
          true,
          "hnsw",
          "manifest",
          1,
          "golden",
          "hash",
          true);

  private static QueryProperties productionLikeProperties(
      int topK, boolean queryDecompositionEnabled) {
    return new QueryProperties(topK, 25, 1.0, 0.3, 1.0, queryDecompositionEnabled, 3, 2, true);
  }

  private static List<GoldenCase> oneCase() {
    return List.of(
        new GoldenCase(
            "a",
            "test",
            "frage",
            List.of("a.md"),
            "cat",
            "easy",
            "de",
            "t",
            null,
            null,
            null,
            null,
            null));
  }

  /**
   * The blocking property: the pipeline path is an observation, and an observation that fails must
   * not fail the harness run it rides along in — otherwise {@code BaselineRegressionTest} never
   * runs and the nightly job loses its verdict on the raw-vector path entirely.
   */
  @Test
  void aFailingPipelineDoesNotFailTheHarnessRun() {
    QueryService failing = mock(QueryService.class);
    when(failing.retrieveRelevantChunksInGivenScopeWithDecomposition(anyString(), any(), any()))
        .thenThrow(new IllegalStateException("vector store exploded"));

    assertThatCode(
            () ->
                PipelineHarnessSupport.runAndWriteGuarded(
                    EvalDomainConfig.COMIC_CHARACTERS,
                    IDENTITY,
                    failing,
                    productionLikeProperties(8, false),
                    RetrievalPipelineProperties.allStagesEnabled(),
                    // Never dereferenced on this path: the failure happens while querying, before
                    // the run configuration (the only reader of indexing properties) is built.
                    null,
                    UUID.randomUUID(),
                    oneCase(),
                    Instant.now(),
                    LoggerFactory.getLogger(PipelineHarnessSupportTest.class)))
        .doesNotThrowAnyException();
  }

  /**
   * The deliberate exception to the rule above: a configuration under which the reported numbers
   * would not mean what their names say is a setup error, decided before any measurement runs.
   */
  @Test
  void anUnmeasurableConfigurationStillFailsHard() {
    assertThatThrownBy(
            () ->
                runWith(
                    productionLikeProperties(8, true),
                    RetrievalPipelineProperties.allStagesEnabled()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("query-decomposition-enabled");

    assertThatThrownBy(
            () ->
                runWith(
                    productionLikeProperties(5, false),
                    RetrievalPipelineProperties.allStagesEnabled()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("top-k");
  }

  /**
   * A run with a switched-off pipeline stage measures a different pipeline while reporting the same
   * fixed points as a full run - no report field records which stages ran, so the difference would
   * land on the committed baseline as a code change. Making stage selection measurable is a
   * contract change (new fixed point, raised contract version, re-drawn baselines), not a property.
   */
  @Test
  void aRunWithASwitchedOffStageIsRejectedAsUnmeasurable() {
    assertThatThrownBy(
            () ->
                runWith(
                    productionLikeProperties(8, false),
                    new RetrievalPipelineProperties(Set.of(RetrievalStageName.MMR_SELECTION))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("disabled-stages");
  }

  /**
   * Issue #1049: the committed pipeline baseline describes the shipped hybrid configuration, so the
   * path that writes it must not quietly measure the vector-only one. Measuring vector-only is not
   * forbidden - it is a named variant of the Variantenvergleich, which writes no baseline.
   */
  @Test
  void aRunWithoutTheLexicalPathIsRejectedAsUnmeasurable() {
    QueryProperties vectorOnly = new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2, false);

    assertThatThrownBy(() -> runWith(vectorOnly, RetrievalPipelineProperties.allStagesEnabled()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("full-text-search-enabled");
  }

  /** Collaborators are null on purpose: the guard must reject before touching any of them. */
  private static void runWith(
      QueryProperties queryProperties, RetrievalPipelineProperties pipelineProperties) {
    PipelineHarnessSupport.runAndWriteGuarded(
        EvalDomainConfig.COMIC_CHARACTERS,
        IDENTITY,
        null,
        queryProperties,
        pipelineProperties,
        null,
        UUID.randomUUID(),
        oneCase(),
        Instant.now(),
        LoggerFactory.getLogger(PipelineHarnessSupportTest.class));
  }
}
