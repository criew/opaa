package io.opaa.eval;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code city-landmarks} counterpart of {@link PipelineBaselineRegressionTest} (issue #1040):
 * own report file, own pipeline baseline file, own verdict — no shared group and no shared baseline
 * with any other domain or with either domain's raw-vector path.
 */
class CityLandmarksPipelineBaselineRegressionTest {

  private static final Logger log =
      LoggerFactory.getLogger(CityLandmarksPipelineBaselineRegressionTest.class);

  @Test
  void currentPipelineRunStaysWithinToleranceOfTheCommittedPipelineBaseline() throws IOException {
    PipelineBaselineRegressionCheck.run(EvalDomainConfig.CITY_LANDMARKS, log);
  }
}
