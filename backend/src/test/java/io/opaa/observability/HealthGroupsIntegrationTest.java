package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.test.OpaaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.SystemHealthDescriptor;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What a load balancer and a container health check see (#1710): {@code readiness} answers from the
 * process and its database alone, and the three contributors that call a model endpoint or the
 * vector store are reachable through groups of their own instead of deciding the overall status -
 * so an unreachable Ollama leaves both {@code readiness} and {@code /actuator/health} {@code UP},
 * and a health probe costs no model call.
 */
@OpaaIntegrationTest
class HealthGroupsIntegrationTest {

  @Autowired private HealthEndpoint healthEndpoint;
  @Autowired private MockMvc mockMvc;

  @Test
  void theModelContributorsAreReachableByGroupAndStayOutOfTheOverallStatus() {
    SystemHealthDescriptor overall = (SystemHealthDescriptor) healthEndpoint.health();

    assertThat(overall.getComponents())
        .doesNotContainKeys(
            ModelHealthGroupsConfiguration.CHAT_CONTRIBUTOR,
            ModelHealthGroupsConfiguration.EMBEDDINGS_CONTRIBUTOR,
            ModelHealthGroupsConfiguration.VECTOR_STORE_CONTRIBUTOR);
    assertThat(overall.getComponents()).containsKey("db");
    assertThat(overall.getGroups())
        .contains(
            ModelHealthGroupsConfiguration.CHAT_GROUP,
            ModelHealthGroupsConfiguration.EMBEDDINGS_GROUP,
            ModelHealthGroupsConfiguration.VECTOR_STORE_GROUP,
            // the group the three new post-processors must not swallow while they wrap each other
            "mail",
            "readiness",
            "liveness");

    assertGroupHoldsOnly(
        ModelHealthGroupsConfiguration.CHAT_GROUP, ModelHealthGroupsConfiguration.CHAT_CONTRIBUTOR);
    assertGroupHoldsOnly(
        ModelHealthGroupsConfiguration.EMBEDDINGS_GROUP,
        ModelHealthGroupsConfiguration.EMBEDDINGS_CONTRIBUTOR);
    assertGroupHoldsOnly(
        ModelHealthGroupsConfiguration.VECTOR_STORE_GROUP,
        ModelHealthGroupsConfiguration.VECTOR_STORE_CONTRIBUTOR);
  }

  @Test
  void readinessAnswersFromTheProcessAndItsDatabaseOnly() {
    CompositeHealthDescriptor readiness =
        (CompositeHealthDescriptor) healthEndpoint.healthForPath("readiness");

    assertThat(readiness.getStatus()).isEqualTo(Status.UP);
    assertThat(readiness.getComponents()).containsOnlyKeys("db", "readinessState");
  }

  @Test
  void bothProbesAnswerOverHttp() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  private void assertGroupHoldsOnly(String group, String contributor) {
    CompositeHealthDescriptor descriptor =
        (CompositeHealthDescriptor) healthEndpoint.healthForPath(group);
    assertThat(descriptor.getComponents()).containsOnlyKeys(contributor);
  }
}
