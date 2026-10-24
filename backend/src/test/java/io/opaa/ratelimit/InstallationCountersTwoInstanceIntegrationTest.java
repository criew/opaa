package io.opaa.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.ratelimit.RateLimitService.Decision;
import io.opaa.test.GlobalCeilingWindow;
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

  /** Long enough that waiting for room in it is rarely needed. */
  private static final int LONG_WINDOW_SECONDS = 3600;

  private static final Duration LONG_WINDOW = Duration.ofSeconds(LONG_WINDOW_SECONDS);

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

  /**
   * Right after a window change the requests of the previous window still count, weighted by the
   * share of it the sliding window still covers: a ceiling exhausted just before the change refuses
   * just after it, although the new fixed window is still empty.
   */
  @Test
  void afterAWindowChangeThePreviousWindowCountsByTheShareItStillCovers() throws Exception {
    GlobalCeilingWindow.startWithRoomIn(jdbcTemplate, LONG_WINDOW_SECONDS);
    seedWindowsBack(1, 1000);
    double into = secondsIntoWindow();

    // the weighted previous window, with a margin for the seconds the test itself takes
    int belowWeighted = (int) Math.floor(1000 * (1 - (into + 5) / LONG_WINDOW_SECONDS));
    assertThat(instanceA.tryCount(key, LONG_WINDOW, belowWeighted).allowed()).isFalse();

    int aboveWeighted = (int) Math.ceil(1000 * (1 - into / LONG_WINDOW_SECONDS)) + 1;
    assertThat(instanceA.tryCount(key, LONG_WINDOW, aboveWeighted).allowed()).isTrue();
    assertThat(storedCounts()).containsExactly(1000, 1);
  }

  @Test
  void aWindowOlderThanThePreviousOneCountsNoLonger() throws Exception {
    GlobalCeilingWindow.startWithRoomIn(jdbcTemplate, LONG_WINDOW_SECONDS);
    seedWindowsBack(2, 1000);

    assertThat(instanceA.tryCount(key, LONG_WINDOW, 1).allowed()).isTrue();
    assertThat(storedCounts()).containsExactly(0, 1);
  }

  /** A row whose current window lies {@code windowsBack} fixed windows before the one of now. */
  private void seedWindowsBack(int windowsBack, int count) {
    jdbcTemplate.update(
        "INSERT INTO installation_counters (counter_key, window_seconds, window_start,"
            + " current_count, previous_count, counted_at) SELECT ?, ?, w, ?, 0, w FROM (SELECT"
            + " date_bin(make_interval(secs => ?), clock_timestamp(), TIMESTAMPTZ 'epoch')"
            + " - make_interval(secs => ?) AS w) s",
        key,
        LONG_WINDOW_SECONDS,
        count,
        LONG_WINDOW_SECONDS,
        windowsBack * LONG_WINDOW_SECONDS);
  }

  private double secondsIntoWindow() {
    return jdbcTemplate.queryForObject(
        "SELECT mod(extract(epoch FROM clock_timestamp()), ?)", Double.class, LONG_WINDOW_SECONDS);
  }

  /** The previous and the current count of the row. */
  private List<Integer> storedCounts() {
    return jdbcTemplate.queryForObject(
        "SELECT previous_count, current_count FROM installation_counters WHERE counter_key = ?",
        (rs, row) -> List.of(rs.getInt(1), rs.getInt(2)),
        key);
  }

  private JdbcInstallationCounters instanceB() {
    return new JdbcInstallationCounters(
        new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource));
  }
}
