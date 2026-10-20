package io.opaa.instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * The {@link InvalidationBus} over Postgres {@code LISTEN}/{@code NOTIFY} (ADR-0053, Entscheidung
 * 5). It listens on a connection of its own outside the pool, held for the life of the instance,
 * and hands every announcement to the bound {@link InvalidationSubscriber}s on one dispatch thread,
 * in the order of the commits that sent them.
 *
 * <p>The connection is checked every {@code checkInterval}. Announcements sent while it is down are
 * lost; so on its loss, on every failed attempt to restore it and once more when it listens again,
 * every subscriber drops everything, and in between {@link #isListening()} tells the caches to read
 * past themselves. The listening connection needs a direct connection or session pooling; a
 * transaction-pooling proxy does not carry {@code LISTEN}.
 *
 * <p>Per instance by design (ADR-0021): every instance hears the bus itself.
 */
public class PostgresInvalidationBus implements InvalidationBus, SmartLifecycle {

  /**
   * The one channel of the bus; the trigger function {@code notify_cache_invalidation} sends here.
   */
  public static final String CHANNEL = "opaa_invalidation";

  /** How often the listening connection is checked and, while it is down, re-opened. */
  public static final Duration CHECK_INTERVAL = Duration.ofSeconds(5);

  /** The application name the listening connection reports in {@code pg_stat_activity}. */
  public static final String APPLICATION_NAME = "opaa-invalidation-bus";

  /** Before the instance registers and everything after it starts; after all of it stops. */
  static final int PHASE = CurrentInstance.PHASE - 1;

  private static final Logger log = LoggerFactory.getLogger(PostgresInvalidationBus.class);

  private static final Pattern TOPIC = Pattern.compile("[a-z][a-z0-9-]{0,62}");
  private static final String BARRIER_PREFIX = "barrier:";

  /** Opens the listening connection; a connection of its own, never one of the pool. */
  @FunctionalInterface
  public interface ConnectionOpener {
    Connection open() throws SQLException;
  }

  private final JdbcOperations jdbc;
  private final DataSource dataSource;
  private final ConnectionOpener opener;
  private final ObjectProvider<InvalidationSubscriber> subscriberProvider;
  private final Duration checkInterval;
  private final Map<String, CountDownLatch> barriers = new ConcurrentHashMap<>();
  private final Object lifecycle = new Object();

  private volatile List<InvalidationSubscriber> subscribers = List.of();
  private volatile boolean running;
  private volatile boolean listening;
  private volatile boolean lossReported;
  private volatile Thread listener;
  private volatile ExecutorService dispatcher;
  private volatile Connection connection;

  public PostgresInvalidationBus(
      JdbcOperations jdbc,
      DataSource dataSource,
      ConnectionOpener opener,
      ObjectProvider<InvalidationSubscriber> subscriberProvider,
      Duration checkInterval) {
    this.jdbc = jdbc;
    this.dataSource = dataSource;
    this.opener = opener;
    this.subscriberProvider = subscriberProvider;
    this.checkInterval = Objects.requireNonNull(checkInterval, "checkInterval");
  }

  /**
   * Opens connections to the application's own database with the address and credentials the pool
   * uses, under {@code applicationName}; a read on it gives up after three check intervals, so a
   * silent network failure surfaces at the next check instead of blocking the listener.
   */
  public static ConnectionOpener opener(
      String jdbcUrl, String username, String password, String applicationName) {
    return () -> {
      PGSimpleDataSource database = new PGSimpleDataSource();
      database.setURL(jdbcUrl);
      database.setUser(username);
      database.setPassword(password);
      database.setApplicationName(applicationName);
      database.setTcpKeepAlive(true);
      database.setSocketTimeout((int) CHECK_INTERVAL.multipliedBy(3).toSeconds());
      return database.getConnection();
    };
  }

  @Override
  public void publish(String topic) {
    requireTopic(topic);
    jdbc.query("SELECT pg_notify(?, ?)", resultSet -> null, CHANNEL, topic);
  }

  @Override
  public boolean isListening() {
    return listening;
  }

  /**
   * Waits until every announcement committed before this call has been handed to the subscribers:
   * Postgres delivers in commit order, and one dispatch thread hands them on in that order. Sent
   * over a connection of its own, so an open transaction of the caller cannot hold it back. Returns
   * {@code false} while the bus does not listen - the caches read past themselves then - or when
   * {@code timeout} passed first.
   */
  public boolean awaitDelivery(Duration timeout) {
    if (!listening) {
      return false;
    }
    String id = UUID.randomUUID().toString();
    CountDownLatch delivered = new CountDownLatch(1);
    barriers.put(id, delivered);
    try {
      try (Connection own = dataSource.getConnection();
          PreparedStatement notify = own.prepareStatement("SELECT pg_notify(?, ?)")) {
        if (!own.getAutoCommit()) {
          own.setAutoCommit(true);
        }
        notify.setString(1, CHANNEL);
        notify.setString(2, BARRIER_PREFIX + id);
        notify.execute();
      }
      return delivered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (SQLException e) {
      log.warn("Could not send a delivery barrier on the invalidation bus: {}", e.getMessage());
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      barriers.remove(id);
    }
  }

  @Override
  public void start() {
    synchronized (lifecycle) {
      if (running) {
        return;
      }
      subscribers = subscriberProvider.orderedStream().toList();
      dispatcher =
          Executors.newSingleThreadExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "cache-invalidation");
                thread.setDaemon(true);
                return thread;
              });
      running = true;
      CountDownLatch firstAttempt = new CountDownLatch(1);
      Thread thread = new Thread(() -> listen(firstAttempt), "invalidation-listener");
      thread.setDaemon(true);
      listener = thread;
      thread.start();
      try {
        // Serve with a listening bus where the database allows it; a failed first attempt is
        // retried by the listener, and the caches read past themselves meanwhile.
        firstAttempt.await(checkInterval.multipliedBy(2).toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void stop() {
    synchronized (lifecycle) {
      if (!running) {
        return;
      }
      running = false;
      listening = false;
      closeQuietly(connection);
      Thread thread = listener;
      listener = null;
      if (thread != null) {
        thread.interrupt();
        try {
          thread.join(checkInterval.multipliedBy(2).toMillis());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      ExecutorService executor = dispatcher;
      dispatcher = null;
      if (executor != null) {
        executor.shutdown();
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return PHASE;
  }

  private void listen(CountDownLatch firstAttempt) {
    while (running) {
      Connection current = null;
      try {
        current = opener.open();
        connection = current;
        try (Statement statement = current.createStatement()) {
          statement.execute("LISTEN " + CHANNEL);
        }
        PGConnection notifications = current.unwrap(PGConnection.class);
        becameListening();
        firstAttempt.countDown();
        receive(current, notifications);
      } catch (SQLException | RuntimeException e) {
        if (running) {
          lost(e);
        }
      } finally {
        firstAttempt.countDown();
        connection = null;
        closeQuietly(current);
      }
      pauseBeforeRetry();
    }
  }

  private void receive(Connection current, PGConnection notifications) throws SQLException {
    long lastCheck = System.nanoTime();
    int timeout = (int) checkInterval.toMillis();
    while (running) {
      PGNotification[] received = notifications.getNotifications(timeout);
      if (received != null) {
        for (PGNotification notification : received) {
          if (CHANNEL.equals(notification.getName())) {
            dispatch(notification.getParameter());
          }
        }
      }
      if (System.nanoTime() - lastCheck >= checkInterval.toNanos()) {
        try (Statement check = current.createStatement()) {
          check.execute("SELECT 1");
        }
        lastCheck = System.nanoTime();
      }
    }
  }

  private void becameListening() {
    listening = true;
    if (lossReported) {
      log.info("Listening on the invalidation bus again; every bound cache is dropped once more");
    }
    lossReported = false;
    dropAll();
  }

  private void lost(Exception cause) {
    boolean wasListening = listening;
    listening = false;
    if (wasListening || !lossReported) {
      log.warn(
          "The invalidation bus does not listen ({}); the bound caches read past themselves until"
              + " it listens again",
          cause.getMessage());
      lossReported = true;
    } else {
      log.debug("The invalidation bus still does not listen: {}", cause.getMessage());
    }
    dropAll();
  }

  private void dispatch(String payload) {
    if (payload == null) {
      return;
    }
    if (payload.startsWith(BARRIER_PREFIX)) {
      String id = payload.substring(BARRIER_PREFIX.length());
      submit(
          () -> {
            CountDownLatch barrier = barriers.get(id);
            if (barrier != null) {
              barrier.countDown();
            }
          });
      return;
    }
    submit(
        () -> {
          for (InvalidationSubscriber subscriber : subscribers) {
            if (subscriber.invalidationTopics().contains(payload)) {
              invalidate(subscriber);
            }
          }
        });
  }

  private void dropAll() {
    submit(() -> subscribers.forEach(PostgresInvalidationBus::invalidate));
  }

  private void submit(Runnable task) {
    ExecutorService executor = dispatcher;
    if (executor != null && !executor.isShutdown()) {
      executor.execute(task);
    }
  }

  private static void invalidate(InvalidationSubscriber subscriber) {
    try {
      subscriber.invalidateAll();
    } catch (RuntimeException e) {
      log.warn(
          "Dropping the cache {} failed: {}", subscriber.getClass().getSimpleName(), e.toString());
    }
  }

  private void pauseBeforeRetry() {
    if (!running) {
      return;
    }
    try {
      Thread.sleep(checkInterval.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void requireTopic(String topic) {
    if (topic == null || !TOPIC.matcher(topic).matches()) {
      throw new IllegalArgumentException("not a topic of the invalidation bus: " + topic);
    }
  }

  private static void closeQuietly(Connection current) {
    if (current == null) {
      return;
    }
    try {
      current.close();
    } catch (SQLException e) {
      log.debug("Closing the listening connection failed: {}", e.getMessage());
    }
  }
}
