package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The wiring #1053 depends on: exactly one {@link RerankRoleStatusProvider} in the running context,
 * and it is the rerank model role itself. {@code RerankRoleConfiguration}'s fallback provider
 * exists for a deployment in which the role is not built; now that it is, the fallback must step
 * aside rather than compete with it - a second bean would break every injection point at startup.
 */
@OpaaIntegrationTest
class RerankRoleStatusProviderIntegrationTest {

  @Autowired private RerankRoleStatusProvider provider;

  @Test
  void theRerankModelRoleIsTheOnlyStatusProvider() {
    assertThat(provider).isInstanceOf(RerankModelRole.class);
  }

  /** The shipped configuration: the switch is off, and the state says so rather than "broken". */
  @Test
  void theShippedConfigurationReportsTheRoleAsSwitchedOff() {
    RerankRoleStatus status = provider.currentStatus();

    assertThat(status.state()).isEqualTo(RerankRoleState.DISABLED);
    assertThat(status.baseUrl()).isNull();
    assertThat(status.modelIdentifier()).isNull();
    assertThat(status.diagnostic()).isNull();
  }
}
