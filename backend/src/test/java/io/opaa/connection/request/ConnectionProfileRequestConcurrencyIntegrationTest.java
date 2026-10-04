package io.opaa.connection.request;

import static org.assertj.core.api.Assertions.assertThat;

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

  @Test
  void ofTwoParallelResolutionsTheSecondIsRefusedAsNoLongerOpen() throws Exception {
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
    try {
      CurrentUser administrator =
          CurrentUser.of(admin, Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, "Verwaltung");

      List<Throwable> refusals =
          inParallel(
              2,
              i ->
                  requests.resolve(
                      administrator, request, ProfileRequestState.DECLINED, null, "Nein " + i));

      assertThat(refusals)
          .singleElement()
          .isInstanceOfSatisfying(
              ConflictException.class,
              refusal ->
                  assertThat(refusal.getCode())
                      .isEqualTo(ConnectionProfileRequestService.NOT_OPEN));
    } finally {
      jdbc.update(
          "UPDATE connection_profile_requests SET resolved_by = NULL WHERE resolved_by = ?", admin);
      jdbc.update("DELETE FROM notifications WHERE recipient_user_id = ?", admin);
      jdbc.update("DELETE FROM users WHERE id = ?", admin);
    }
  }

  private List<Throwable> inParallel(IntConsumer call) throws Exception {
    return inParallel(THREADS, call);
  }

  /** Runs {@code call} on {@code threads} threads released together; returns what they threw. */
  private static List<Throwable> inParallel(int threads, IntConsumer call) throws Exception {
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
