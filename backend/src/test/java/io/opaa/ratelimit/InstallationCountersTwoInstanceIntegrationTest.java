package io.opaa.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.ratelimit.RateLimitService.Decision;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.TwoInstanceRace;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The installation-wide budgets of ADR-0053, Entscheidung 12, with two instances against one
 * database: instance A is the context's {@link JdbcInstallationCounters}, instance B one of its own
 * over the same database. A global ceiling admits its budget once for both together, never once per
 * instance; the alert after a channel-wide count is raised by exactly one of them.
 */
@OpaaIntegrationTest
class InstallationCountersTwoInstanceIntegrationTest {

  private static final Duration MINUTE = Duration.ofMinutes(1);

  @Autowired private JdbcInstallationCounters instanceA;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final String key = "test:" + UUID.randomUUID();
  private final List<String> keys = new ArrayList<>(List.of(key));

  @AfterEach
  void tearDown() {
    for (String own : keys) {
      jdbcTemplate.update("DELETE FROM installation_counters WHERE counter_key = ?", own);
    }
  }

  @Test
  void aGlobalCeilingAdmitsItsBudgetOnceForBothInstancesTogether() {
    String rule = "two-instances-" + UUID.randomUUID();
    keys.add(InstallationRateLimit.KEY_PREFIX + rule);
    RequestLimiter onA = new InstallationRateLimit(instanceA, rule, 5, 60);
    RequestLimiter onB = new InstallationRateLimit(instanceB(), rule, 5, 60);

    int admitted = 0;
    for (int request = 0; request < 10; request++) {
      RequestLimiter instance = request % 2 == 0 ? onA : onB;
      if (instance.tryAcquire("__global__").allowed()) {
        admitted++;
      }
    }

    assertThat(admitted).isEqualTo(5);
  }

  @Test
  void aRefusalNamesTheSecondsUntilTheWindowHasRoom() {
    for (int request = 0; request < 3; request++) {
      assertThat(instanceA.tryCount(key, MINUTE, 3).allowed()).isTrue();
    }

    Decision refused = instanceB().tryCount(key, MINUTE, 3);

    assertThat(refused.allowed()).isFalse();
    assertThat(refused.retryAfterSeconds()).isBetween(1L, 120L);
  }

  @Test
  void aRequestGivenBackOnOneInstanceFreesItsPlaceForTheOther() {
    Decision granted = instanceA.tryCount(key, MINUTE, 1);
    JdbcInstallationCounters onB = instanceB();
    assertThat(onB.tryCount(key, MINUTE, 1).allowed()).isFalse();

    instanceA.release(key, MINUTE, granted);

    assertThat(onB.tryCount(key, MINUTE, 1).allowed()).isTrue();
  }

  /**
   * Both instances count the last place at the same moment; the row lock and the re-evaluated
   * condition of the upsert admit exactly one.
   */
  @Test
  void twoInstancesRacingForTheLastPlaceAdmitExactlyOne() throws Exception {
    assertThat(instanceA.tryCount(key, MINUTE, 2).allowed()).isTrue();
    JdbcInstallationCounters onB = instanceB();
    List<Boolean> outcomes = new CopyOnWriteArrayList<>();

    new TwoInstanceRace(dataSource)
        .run(
            () -> outcomes.add(instanceA.tryCount(key, MINUTE, 2).allowed()),
            () -> outcomes.add(onB.tryCount(key, MINUTE, 2).allowed()),
            "SELECT 1 FROM installation_counters WHERE counter_key = ? FOR UPDATE",
            key);

    assertThat(outcomes).containsExactlyInAnyOrder(true, false);
  }

  @Test
  void aChannelCountSeesTheEventsOfBothInstances() {
    JdbcInstallationCounters onB = instanceB();
    instanceA.count(key, Duration.ofHours(1));
    onB.count(key, Duration.ofHours(1));

    assertThat(instanceA.count(key, Duration.ofHours(1))).isGreaterThanOrEqualTo(3.0);
  }

  @Test
  void theQuietPeriodAfterAnAlertIsClaimedByExactlyOneInstance() {
    JdbcInstallationCounters onB = instanceB();
    instanceA.count(key, Duration.ofHours(1));

    boolean claimedByA = instanceA.claimQuietPeriod(key, Duration.ofHours(1), Duration.ofHours(6));
    boolean claimedByB = onB.claimQuietPeriod(key, Duration.ofHours(1), Duration.ofHours(6));

    assertThat(claimedByA).isTrue();
    assertThat(claimedByB).isFalse();
  }

  @Test
  void theWaitAfterAFullWindowCoversTheWindowThatBecomesThePreviousOne() {
    // a full current window: it has to pass and then weigh below the bound as the previous one
    assertThat(JdbcInstallationCounters.waitSeconds(0, 10, 15, 60, 10)).isEqualTo(45);
    // a previous window of twenty alone: room once it weighs below ten, half-way through
    assertThat(JdbcInstallationCounters.waitSeconds(20, 0, 10, 60, 10)).isEqualTo(20);
  }

  private JdbcInstallationCounters instanceB() {
    return new JdbcInstallationCounters(
        new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource));
  }
}
