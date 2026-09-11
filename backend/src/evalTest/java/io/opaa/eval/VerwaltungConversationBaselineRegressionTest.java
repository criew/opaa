package io.opaa.eval;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code verwaltung} domain's multi-turn baseline verdict (issue #1553): own report file, own
 * baseline file, own Gradle task — no shared verdict with this domain's raw-vector or pipeline
 * path.
 *
 * <p>Consumes the report of {@code evaluateVerwaltungConversations}, which is the only task that
 * switches the multi-turn step on; run on its own it fails on the missing report rather than
 * silently passing.
 */
class VerwaltungConversationBaselineRegressionTest {

  private static final Logger log =
      LoggerFactory.getLogger(VerwaltungConversationBaselineRegressionTest.class);

  @Test
  void currentMultiTurnRunStaysWithinToleranceOfTheCommittedConversationBaseline()
      throws IOException {
    ConversationBaselineRegressionCheck.run(EvalDomainConfig.VERWALTUNG, log);
  }
}
