package io.opaa.eval;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code city-landmarks} counterpart of {@link PipelineBaselineRegressionTest} (issue #1040):
 * own report file, own pipeline baseline file, own verdict — no shared group and no shared baseline
 * with any other domain or with either domain's raw-vector path.
 *
 * <p><b>Not yet wired into {@code checkCityLandmarksRetrievalBaseline} (issue #1081).</b> This
 * domain's pipeline baseline has not been drawn yet, and running the check without a committed
 * baseline would turn the nightly job red for a measurement that never happened. The class is
 * complete and gets hooked up together with {@code eval/baseline/pipeline-city-landmarks.json}; see
 * the {@code pipelineBaselineTestClass = null} call site in {@code backend/build.gradle.kts}.
 */
class CityLandmarksPipelineBaselineRegressionTest {

  private static final Logger log =
      LoggerFactory.getLogger(CityLandmarksPipelineBaselineRegressionTest.class);

  @Test
  void currentPipelineRunStaysWithinToleranceOfTheCommittedPipelineBaseline() throws IOException {
    PipelineBaselineRegressionCheck.run(EvalDomainConfig.CITY_LANDMARKS, log);
  }
}
