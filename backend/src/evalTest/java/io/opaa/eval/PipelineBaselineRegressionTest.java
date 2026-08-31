package io.opaa.eval;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compares the most recent {@code evaluateRetrieval} run's pipeline report against the committed
 * {@code pipeline-comic-characters} baseline (issue #1040) — the pipeline path's counterpart of
 * {@link BaselineRegressionTest}, running beside it in the same {@code checkRetrievalBaseline} task
 * so both paths are judged on every nightly run, each against its own baseline.
 *
 * <p>Excluded from {@code evalUnitTest} for the same reason as its raw-vector counterpart: it
 * depends on a report file that only exists after a real, multi-minute evaluation run.
 */
class PipelineBaselineRegressionTest {

  private static final Logger log = LoggerFactory.getLogger(PipelineBaselineRegressionTest.class);

  @Test
  void currentPipelineRunStaysWithinToleranceOfTheCommittedPipelineBaseline() throws IOException {
    PipelineBaselineRegressionCheck.run(EvalDomainConfig.COMIC_CHARACTERS, log);
  }
}
