package io.opaa;

import io.opaa.test.OpaaIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke-tests that the Spring context starts at all. Uses {@link OpaaIntegrationTest} - the same
 * canonical signature as the rest of the shared-context integration test group (e.g. {@code
 * SpaceServiceIntegrationTest}) - so this test shares that context and Testcontainers Postgres
 * instance instead of paying for a second, otherwise redundant one just to prove the app starts
 * (issue #497, formalized into a meta-annotation by #843).
 */
@OpaaIntegrationTest
class OpaaApplicationTests {

  @Test
  void contextLoads() {}
}
