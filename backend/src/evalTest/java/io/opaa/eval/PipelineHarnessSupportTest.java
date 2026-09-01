package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.query.QueryProperties;
import io.opaa.query.QueryService;
import java.time.Instant;
import java.util.List;
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
          "ollama", "model", "digest", "image", 768, true, "hnsw", "manifest", 1, "golden", "hash");

  private static QueryProperties productionLikeProperties(
      int topK, boolean queryDecompositionEnabled) {
    return new QueryProperties(topK, 25, 1.0, 0.3, 1.0, queryDecompositionEnabled, 3, 2);
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
    assertThatThrownBy(() -> runWith(productionLikeProperties(8, true)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("query-decomposition-enabled");

    assertThatThrownBy(() -> runWith(productionLikeProperties(5, false)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("top-k");
  }

  /** Collaborators are null on purpose: the guard must reject before touching any of them. */
  private static void runWith(QueryProperties queryProperties) {
    PipelineHarnessSupport.runAndWriteGuarded(
        EvalDomainConfig.COMIC_CHARACTERS,
        IDENTITY,
        null,
        queryProperties,
        null,
        UUID.randomUUID(),
        oneCase(),
        Instant.now(),
        LoggerFactory.getLogger(PipelineHarnessSupportTest.class));
  }
}
