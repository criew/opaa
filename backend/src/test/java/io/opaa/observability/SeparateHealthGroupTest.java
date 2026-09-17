package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;

/**
 * The one grouping rule behind every contributor that depends on a foreign service - upload store,
 * mail, chat, embeddings, vector store (ADR-0030 Entscheidung 9, #1710): the named contributors
 * leave the primary group, whose other members and rules stay, and get a group of their own that
 * shows nothing else. Every other group keeps its own membership.
 */
class SeparateHealthGroupTest {

  private static final HealthEndpointGroup EVERYTHING =
      new HealthEndpointGroup() {
        @Override
        public boolean isMember(String name) {
          return true;
        }

        @Override
        public boolean showComponents(SecurityContext securityContext) {
          return true;
        }

        @Override
        public boolean showDetails(SecurityContext securityContext) {
          return false;
        }

        @Override
        public StatusAggregator getStatusAggregator() {
          return StatusAggregator.getDefault();
        }

        @Override
        public HttpCodeStatusMapper getHttpCodeStatusMapper() {
          return status -> 200;
        }

        @Override
        public AdditionalHealthEndpointPath getAdditionalPath() {
          return AdditionalHealthEndpointPath.from("server:/gesundheit");
        }
      };

  private static final HealthEndpointGroup READINESS =
      new HealthEndpointGroup() {
        @Override
        public boolean isMember(String name) {
          return "db".equals(name) || "readinessState".equals(name);
        }

        @Override
        public boolean showComponents(SecurityContext securityContext) {
          return true;
        }

        @Override
        public boolean showDetails(SecurityContext securityContext) {
          return false;
        }

        @Override
        public StatusAggregator getStatusAggregator() {
          return StatusAggregator.getDefault();
        }

        @Override
        public HttpCodeStatusMapper getHttpCodeStatusMapper() {
          return status -> 200;
        }

        @Override
        public AdditionalHealthEndpointPath getAdditionalPath() {
          return null;
        }
      };

  @Test
  void theContributorLeavesThePrimaryGroupAndGetsItsOwn() {
    HealthEndpointGroups groups =
        new SeparateHealthGroup("mail", Set.of("mailDelivery"))
            .postProcessHealthEndpointGroups(
                HealthEndpointGroups.of(EVERYTHING, Map.of("liveness", EVERYTHING)));

    HealthEndpointGroup primary = groups.getPrimary();
    assertThat(primary.isMember("db")).isTrue();
    assertThat(primary.isMember("chat")).isTrue();
    assertThat(primary.isMember("mailDelivery")).isFalse();
    assertThat(primary.showComponents(null)).isTrue();
    assertThat(primary.showDetails(null)).isFalse();
    assertThat(primary.getAdditionalPath()).isEqualTo(EVERYTHING.getAdditionalPath());

    assertThat(groups.getNames()).containsExactlyInAnyOrder("liveness", "mail");
    HealthEndpointGroup own = groups.get("mail");
    assertThat(own.isMember("mailDelivery")).isTrue();
    assertThat(own.isMember("db")).isFalse();
    assertThat(own.getAdditionalPath()).isNull();
    assertThat(own.getStatusAggregator()).isNotNull();
    assertThat(groups.get("liveness").isMember("mailDelivery")).isTrue();
  }

  @Test
  void aGroupOfSeveralContributorsExcludesAllOfThemFromThePrimaryGroup() {
    HealthEndpointGroups groups =
        new SeparateHealthGroup("models", Set.of("chat", "embeddings"))
            .postProcessHealthEndpointGroups(
                HealthEndpointGroups.of(EVERYTHING, Map.of("readiness", READINESS)));

    HealthEndpointGroup primary = groups.getPrimary();
    assertThat(primary.isMember("chat")).isFalse();
    assertThat(primary.isMember("embeddings")).isFalse();
    assertThat(primary.isMember("db")).isTrue();

    HealthEndpointGroup own = groups.get("models");
    assertThat(own.isMember("chat")).isTrue();
    assertThat(own.isMember("embeddings")).isTrue();
    assertThat(own.isMember("db")).isFalse();
  }

  @Test
  void stackedPostProcessorsKeepEachOthersGroupsAndExclusions() {
    HealthEndpointGroups groups =
        new SeparateHealthGroup("embedding-model", Set.of("embeddings"))
            .postProcessHealthEndpointGroups(
                new SeparateHealthGroup("chat-model", Set.of("chat"))
                    .postProcessHealthEndpointGroups(
                        HealthEndpointGroups.of(EVERYTHING, Map.of("readiness", READINESS))));

    HealthEndpointGroup primary = groups.getPrimary();
    assertThat(primary.isMember("chat")).isFalse();
    assertThat(primary.isMember("embeddings")).isFalse();
    assertThat(primary.isMember("db")).isTrue();

    assertThat(groups.getNames())
        .containsExactlyInAnyOrder("readiness", "chat-model", "embedding-model");
    assertThat(groups.get("chat-model").isMember("chat")).isTrue();
    assertThat(groups.get("embedding-model").isMember("embeddings")).isTrue();

    // The readiness group is what a load balancer probes: it keeps exactly its own members, and
    // neither post-processor takes the model contributors out of it - they were never in it.
    HealthEndpointGroup readiness = groups.get("readiness");
    assertThat(readiness.isMember("db")).isTrue();
    assertThat(readiness.isMember("readinessState")).isTrue();
    assertThat(readiness.isMember("chat")).isFalse();
    assertThat(readiness.isMember("embeddings")).isFalse();
  }
}
