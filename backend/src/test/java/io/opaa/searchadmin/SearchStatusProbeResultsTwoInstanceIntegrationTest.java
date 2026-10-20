package io.opaa.searchadmin;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.ModelRole;
import io.opaa.api.types.ModelRoleCondition;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The probes of the search status page with two instances against one database (ADR-0053,
 * Entscheidung 12): instance A is this context's {@link JdbcSearchStatusProbeResults}, instance B
 * one of its own. Within the shared time span both show the result shared first, even when B probed
 * something else meanwhile; once it is older, B's own probe takes its place for both.
 */
@OpaaIntegrationTest
class SearchStatusProbeResultsTwoInstanceIntegrationTest {

  private static final Duration TTL = SearchStatusService.PROBE_CACHE_TTL;

  @Autowired private JdbcSearchStatusProbeResults instanceA;
  @Autowired private JdbcTemplate jdbc;

  private JdbcSearchStatusProbeResults instanceB;

  @BeforeEach
  void setUp() {
    instanceB = new JdbcSearchStatusProbeResults(jdbc);
    instanceA.forget();
  }

  @AfterEach
  void tearDown() {
    instanceA.forget();
  }

  @Test
  void bothInstancesShowTheProbeSharedFirst() {
    ModelRoleStatus reachedFromA = embedding(ModelRoleCondition.ACTIVE);
    ModelRoleStatus missedFromB = embedding(ModelRoleCondition.UNREACHABLE);

    instanceA.share(reachedFromA, TTL);
    ModelRoleStatus shownOnB = instanceB.share(missedFromB, TTL);

    assertThat(shownOnB).isEqualTo(reachedFromA);
    assertThat(instanceB.fresh(ModelRole.EMBEDDING, TTL)).contains(reachedFromA);
    assertThat(instanceA.fresh(ModelRole.EMBEDDING, TTL)).contains(reachedFromA);
  }

  @Test
  void aStaleProbeIsReplacedForBothInstances() {
    instanceA.share(embedding(ModelRoleCondition.ACTIVE), TTL);
    jdbc.update(
        "UPDATE search_status_probes SET observed_at = clock_timestamp() - interval '1 hour'"
            + " WHERE role = ?",
        ModelRole.EMBEDDING.name());
    ModelRoleStatus missedFromB = embedding(ModelRoleCondition.UNREACHABLE);

    assertThat(instanceA.fresh(ModelRole.EMBEDDING, TTL)).isEmpty();
    assertThat(instanceB.share(missedFromB, TTL)).isEqualTo(missedFromB);
    assertThat(instanceA.fresh(ModelRole.EMBEDDING, TTL)).contains(missedFromB);
  }

  @Test
  void aConditionThisVersionDoesNotKnowCountsAsNoProbe() {
    instanceA.share(embedding(ModelRoleCondition.ACTIVE), TTL);
    jdbc.update(
        "UPDATE search_status_probes SET condition = 'FROM_A_LATER_RELEASE' WHERE role = ?",
        ModelRole.EMBEDDING.name());

    assertThat(instanceB.fresh(ModelRole.EMBEDDING, TTL)).isEmpty();
  }

  private static ModelRoleStatus embedding(ModelRoleCondition condition) {
    return new ModelRoleStatus(
        ModelRole.EMBEDDING,
        condition,
        "https://embedding.example.invalid/v1",
        "text-embedding-test",
        "Das Einbettungsmodell: " + condition);
  }
}
