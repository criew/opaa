package io.opaa.library;

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
 * The health grouping of the S3 upload store (ADR-0030, Entscheidung 9): the contributor leaves the
 * primary group, whose other members and rules stay, and gets a group of its own that shows nothing
 * else.
 */
class UploadStoreHealthGroupTest {

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
          return HttpCodeStatusMapper.DEFAULT;
        }

        @Override
        public AdditionalHealthEndpointPath getAdditionalPath() {
          return AdditionalHealthEndpointPath.from("server:/gesundheit");
        }
      };

  @Test
  void theContributorLeavesThePrimaryGroupAndGetsItsOwn() {
    HealthEndpointGroups groups =
        new UploadStoreHealthGroup()
            .postProcessHealthEndpointGroups(
                HealthEndpointGroups.of(EVERYTHING, Map.of("liveness", EVERYTHING)));

    HealthEndpointGroup primary = groups.getPrimary();
    assertThat(primary.isMember("db")).isTrue();
    assertThat(primary.isMember("chat")).isTrue();
    assertThat(primary.isMember(UploadStoreHealthGroup.CONTRIBUTOR)).isFalse();
    assertThat(primary.showComponents(null)).isTrue();
    assertThat(primary.showDetails(null)).isFalse();
    assertThat(primary.getAdditionalPath()).isEqualTo(EVERYTHING.getAdditionalPath());

    assertThat(groups.getNames())
        .containsExactlyInAnyOrder("liveness", UploadStoreHealthGroup.GROUP);
    HealthEndpointGroup own = groups.get(UploadStoreHealthGroup.GROUP);
    assertThat(own.isMember(UploadStoreHealthGroup.CONTRIBUTOR)).isTrue();
    assertThat(own.isMember("db")).isFalse();
    assertThat(own.getAdditionalPath()).isNull();
    assertThat(own.getStatusAggregator()).isNotNull();
    assertThat(groups.get("liveness").isMember(UploadStoreHealthGroup.CONTRIBUTOR)).isTrue();
  }
}
