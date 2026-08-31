package io.opaa.eval;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code verwaltung} counterpart of {@link PipelineBaselineRegressionTest} (issues
 * #1040/#1043): own report file, own pipeline baseline file, own verdict — no shared group and no
 * shared baseline with any other domain or with this domain's raw-vector path.
 *
 * <p>Unlike {@code city-landmarks} (whose pipeline baseline is still outstanding, issue #1081),
 * this domain's pipeline baseline was drawn together with its raw-vector one, so this test is wired
 * into {@code checkVerwaltungRetrievalBaseline} from the start.
 */
class VerwaltungPipelineBaselineRegressionTest {

  private static final Logger log =
      LoggerFactory.getLogger(VerwaltungPipelineBaselineRegressionTest.class);

  @Test
  void currentPipelineRunStaysWithinToleranceOfTheCommittedPipelineBaseline() throws IOException {
    PipelineBaselineRegressionCheck.run(EvalDomainConfig.VERWALTUNG, log);
  }
}
