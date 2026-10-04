package io.opaa.connection.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The per-person limits and the resolution of a request under concurrent calls on real connections:
 * parallel submissions with different addresses never exceed the hourly budget or the ceiling of
 * open requests, and of two parallel resolutions the second is refused as no longer open.
 */
@OpaaIntegrationTest
class ConnectionProfileRequestConcurrencyIntegrationTest {

  private static final int THREADS = 20;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ConnectionProfileRequestService requests;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID person;
  private CurrentUser caller;

  @BeforeEach
  void setUp() {
    person = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Parallel', now(), 'USER', ?)",
        person,
        "parallel-" + person,
        person + "@example.com",
        Organization.DEFAULT_ID);
    caller = CurrentUser.of(person, Organization.DEFAULT_ID, SystemRole.USER, "Parallel");
  }

  @AfterEach
  void tearDown() {
    jdbc.update(
        "DELETE FROM notifications WHERE object_id IN"
            + " (SELECT id FROM connection_profile_requests WHERE requested_by = ?)",
        person);
    jdbc.update("DELETE FROM connection_profile_requests WHERE requested_by = ?", person);
    jdbc.update("DELETE FROM users WHERE id = ?", person);
  }

  @Test
  void parallelSubmissionsStayWithinTheHourlyBudget() throws Exception {
    List<Throwable> refusals =
        inParallel(
            i ->
                requests.submit(
                    caller,
                    ProfileProbeSourceConnector.TYPE,
                    "https://parallel" + i + ".example.org",
                    null));

    assertThat(requestCount()).isEqualTo(5);
    assertThat(refusals)
        .hasSize(THREADS - 5)
        .allMatch(refusal -> refusal instanceof TooManyRequestsException);
    assertThat(administratorNotifications()).isEqualTo(5 * administrators());
  }

  @Test
  void parallelSubmissionsStayWithinTheCeilingOfOpenRequests() throws Exception {
    for (int i = 0; i < 8; i++) {
      jdbc.update(
          "INSERT INTO connection_profile_requests (id, organization_id, source_type, server_url,"
              + " requested_by, created_at, state, version) VALUES (?, ?, 'PROFILE_PROBE', ?, ?,"
              + " now() - interval '2 hours', 'OPEN', 0)",
          UUID.randomUUID(),
          Organization.DEFAULT_ID,
          "https://old" + i + ".example.org",
          person);
    }

    List<Throwable> refusals =
        inParallel(
            i ->
                requests.submit(
                    caller,
                    ProfileProbeSourceConnector.TYPE,
                    "https://more" + i + ".example.org",
                    null));

    assertThat(requestCount()).isEqualTo(10);
    assertThat(refusals)
        .hasSize(THREADS - 2)
        .allMatch(refusal -> refusal instanceof ConflictException);
    assertThat(administratorNotifications()).isEqualTo(2 * administrators());
  }

  /**
   * The first resolution holds its transaction open until the second has either finished or is
   * waiting for a lock; only then does the first commit. Without a lock on the request the second
   * reads it as open and wins, and the first fails on the version - with it the second waits and is
   * refused as no longer open.
   */
  @Test
  void ofTwoOverlappingResolutionsTheSecondIsRefusedAsNoLongerOpen() throws Exception {
    UUID request =
        requests
            .submit(caller, ProfileProbeSourceConnector.TYPE, "https://race.example.org", null)
            .view()
            .request()
            .getId();
    UUID admin = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Verwaltung', now(),"
            + " 'SYSTEM_ADMIN', ?)",
        admin,
        "race-admin-" + admin,
        admin + "@example.com",
        Organization.DEFAULT_ID);
    CurrentUser administrator =
        CurrentUser.of(admin, Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, "Verwaltung");
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    CountDownLatch firstResolved = new CountDownLatch(1);
    CountDownLatch commitFirst = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        requests.resolve(
                            administrator, request, ProfileRequestState.DECLINED, null, "Erste");
                        firstResolved.countDown();
                        await(commitFirst);
                      }));
      assertThat(firstResolved.await(30, TimeUnit.SECONDS)).isTrue();
      Future<?> second =
          executor.submit(
              () ->
                  requests.resolve(
                      administrator, request, ProfileRequestState.DONE, null, "Zweite"));
      waitUntilFinishedOrBlocked(second);
      commitFirst.countDown();

      first.get(30, TimeUnit.SECONDS);
      assertThatThrownBy(() -> second.get(30, TimeUnit.SECONDS))
          .cause()
          .isInstanceOfSatisfying(
              ConflictException.class,
              refusal ->
                  assertThat(refusal.getCode())
                      .isEqualTo(ConnectionProfileRequestService.NOT_OPEN));
      assertThat(
              jdbc.queryForObject(
                  "SELECT answer FROM connection_profile_requests WHERE id = ?",
                  String.class,
                  request))
          .isEqualTo("Erste");
    } finally {
      commitFirst.countDown();
      executor.shutdownNow();
      jdbc.update(
          "UPDATE connection_profile_requests SET resolved_by = NULL WHERE resolved_by = ?", admin);
      jdbc.update("DELETE FROM notifications WHERE recipient_user_id = ?", admin);
      jdbc.update("DELETE FROM users WHERE id = ?", admin);
    }
  }

  /** Waits until {@code call} is done or some session of the database waits for a lock. */
  private void waitUntilFinishedOrBlocked(Future<?> call) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!call.isDone() && System.nanoTime() < deadline) {
      Long waiting =
          jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Long.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(20);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("the test never released the first resolution");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Runs {@code call} on {@link #THREADS} threads released together; returns what they threw. */
  private static List<Throwable> inParallel(IntConsumer call) throws Exception {
    int threads = THREADS;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Throwable> thrown = Collections.synchronizedList(new ArrayList<>());
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        int index = i;
        futures.add(
            executor.submit(
                () -> {
                  try {
                    start.await();
                    call.accept(index);
                  } catch (Throwable e) {
                    thrown.add(e);
                  }
                }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
    return thrown;
  }

  private long requestCount() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connection_profile_requests WHERE requested_by = ?",
        Long.class,
        person);
  }

  private long administratorNotifications() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notifications WHERE type = 'CONNECTION_PROFILE_REQUESTED' AND"
            + " object_id IN (SELECT id FROM connection_profile_requests WHERE requested_by = ?)",
        Long.class,
        person);
  }

  private long administrators() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM users WHERE organization_id = ? AND system_role = 'SYSTEM_ADMIN'",
        Long.class,
        Organization.DEFAULT_ID);
  }
}
