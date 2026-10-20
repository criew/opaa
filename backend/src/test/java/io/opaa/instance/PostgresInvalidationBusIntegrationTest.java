package io.opaa.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opaa.test.OpaaIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The invalidation bus between two instances against one database (ADR-0053, Entscheidung 5): this
 * context sends as instance A, and instance B is a second bus with a listening connection of its
 * own and a recording subscriber. An announcement reaches B with the commit and never after a
 * rollback, the sending instance hears its own, and a lost listening connection drops every bound
 * cache - once when it is lost and once more when the bus listens again.
 */
@OpaaIntegrationTest
class PostgresInvalidationBusIntegrationTest {

  private static final String TOPIC = "bus-probe";
  private static final String APPLICATION_NAME = "opaa-test-bus-probe";
  private static final Duration DELIVERY = Duration.ofSeconds(10);

  @Autowired private InvalidationBus busOfA;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcConnectionDetails connectionDetails;
  @Autowired private TransactionTemplate transactions;

  private final Recording recording = new Recording();
  private final AtomicInteger unboundDrops = new AtomicInteger();
  private PostgresInvalidationBus busOfB;

  @BeforeEach
  void startInstanceB() {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("recording", recording);
    beans.addBean("unbound", new Unbound());
    busOfB =
        new PostgresInvalidationBus(
            jdbc,
            dataSource,
            PostgresInvalidationBus.opener(
                connectionDetails.getJdbcUrl(),
                connectionDetails.getUsername(),
                connectionDetails.getPassword(),
                APPLICATION_NAME),
            beans.getBeanProvider(InvalidationSubscriber.class),
            Duration.ofMillis(200));
    busOfB.start();
    assertThat(busOfB.isListening()).isTrue();
    assertThat(busOfB.awaitDelivery(DELIVERY)).isTrue();
    recording.clear();
  }

  @AfterEach
  void stopInstanceB() {
    busOfB.stop();
  }

  @Test
  void anAnnouncementReachesTheOtherInstanceWithTheCommitAndNeverAfterARollback() {
    transactions.executeWithoutResult(
        status -> {
          busOfA.publish(TOPIC);
          assertThat(busOfB.awaitDelivery(DELIVERY)).isTrue();
          assertThat(recording.events()).as("nothing before the commit").isEmpty();
        });
    assertThat(busOfB.awaitDelivery(DELIVERY)).isTrue();
    assertThat(recording.events()).containsExactly(Recording.DROPPED_LISTENING);

    recording.clear();
    transactions.executeWithoutResult(
        status -> {
          busOfA.publish(TOPIC);
          status.setRollbackOnly();
        });
    assertThat(busOfB.awaitDelivery(DELIVERY)).isTrue();
    assertThat(recording.events()).as("nothing after a rollback").isEmpty();
  }

  @Test
  void theSendingInstanceHearsItsOwnAnnouncement() {
    busOfB.publish(TOPIC);

    assertThat(busOfB.awaitDelivery(DELIVERY)).isTrue();
    assertThat(recording.events()).containsExactly(Recording.DROPPED_LISTENING);
  }

  @Test
  void aLostListeningConnectionDropsEveryBoundCacheAndAgainOnceItListens() {
    Integer terminated =
        jdbc.queryForObject(
            "SELECT count(*) FROM (SELECT pg_terminate_backend(pid) FROM pg_stat_activity"
                + " WHERE application_name = ?) t",
            Integer.class,
            APPLICATION_NAME);
    assertThat(terminated).isEqualTo(1);

    await()
        .atMost(DELIVERY)
        .untilAsserted(
            () ->
                assertThat(recording.events())
                    .containsSubsequence(
                        Recording.DROPPED_WHILE_DEAF, Recording.DROPPED_LISTENING));
    assertThat(busOfB.isListening()).isTrue();
    assertThat(unboundDrops).as("a subscriber that names no topic is not bound").hasValue(0);

    recording.clear();
    busOfA.publish(TOPIC);
    assertThat(busOfB.awaitDelivery(DELIVERY)).isTrue();
    assertThat(recording.events()).containsExactly(Recording.DROPPED_LISTENING);
  }

  /** Names no topic, as a test double of a subscriber does; the bus must never call it. */
  private final class Unbound implements InvalidationSubscriber {

    @Override
    public Set<String> invalidationTopics() {
      return null;
    }

    @Override
    public void invalidateAll() {
      unboundDrops.incrementAndGet();
    }
  }

  /**
   * Records every drop - for an announcement of its topic or for the bus - and whether B listened.
   */
  private final class Recording implements InvalidationSubscriber {

    static final String DROPPED_WHILE_DEAF = "dropped while not listening";
    static final String DROPPED_LISTENING = "dropped while listening";

    private final List<String> events = new CopyOnWriteArrayList<>();

    @Override
    public Set<String> invalidationTopics() {
      return Set.of(TOPIC);
    }

    @Override
    public void invalidateAll() {
      events.add(busOfB.isListening() ? DROPPED_LISTENING : DROPPED_WHILE_DEAF);
    }

    List<String> events() {
      return List.copyOf(events);
    }

    void clear() {
      events.clear();
    }
  }
}
