package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;

/**
 * The health grouping of the mail subsystem (#1559 review, HIGH 4): the {@code mail} contributor
 * leaves the primary group - which is the documented container health check, and a single refused
 * mail must not take an instance out of rotation - and gets a group of its own that shows nothing
 * else.
 */
class MailHealthGroupTest {

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

  @Test
  void theContributorLeavesThePrimaryGroupAndGetsItsOwn() {
    HealthEndpointGroups groups =
        new MailHealthGroup()
            .postProcessHealthEndpointGroups(
                HealthEndpointGroups.of(EVERYTHING, Map.of("liveness", EVERYTHING)));

    HealthEndpointGroup primary = groups.getPrimary();
    assertThat(primary.isMember("db")).isTrue();
    assertThat(primary.isMember("chat")).isTrue();
    assertThat(primary.isMember(MailHealthGroup.CONTRIBUTOR)).isFalse();
    assertThat(primary.showComponents(null)).isTrue();
    assertThat(primary.showDetails(null)).isFalse();
    assertThat(primary.getAdditionalPath()).isEqualTo(EVERYTHING.getAdditionalPath());

    assertThat(groups.getNames()).containsExactlyInAnyOrder("liveness", MailHealthGroup.GROUP);
    HealthEndpointGroup own = groups.get(MailHealthGroup.GROUP);
    assertThat(own.isMember(MailHealthGroup.CONTRIBUTOR)).isTrue();
    assertThat(own.isMember("db")).isFalse();
    assertThat(own.getAdditionalPath()).isNull();
    assertThat(own.getStatusAggregator()).isNotNull();
  }
}
