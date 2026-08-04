package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.TestcontainersConfiguration;
import io.opaa.organization.Organization;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the race described in #300: two or more concurrent <em>first</em> synchronisation runs
 * of the same organization reaching {@link DirectorySyncStatusRecorder#record} together, before
 * either has committed the organization's single {@code directory_sync_status} row. Nothing
 * serialises concurrent runs today (see {@link DirectorySyncService}'s class javadoc), so two
 * administrators triggering a first run at the same time is constructible, if rare.
 *
 * <p>Runs against a real Postgres instance with the real, versioned Liquibase schema ({@code
 * spring.liquibase.enabled=true}, {@code ddl-auto=none}) and real threads, following {@code
 * UserServiceCreationRaceIntegrationTest}'s pattern - a mocked transaction manager would only
 * exercise the catch block and not the propagation and visibility semantics the fix depends on,
 * which is precisely where the two previous attempts at this class of fix went wrong (#280, #297).
 *
 * <p>Before the fix, {@link #concurrentFirstRunsOfTheSameOrganizationRecordExactlyOneStatusRow()}
 * fails: the losers of the race throw {@link
 * org.springframework.dao.DataIntegrityViolationException} with {@code duplicate key value violates
 * unique constraint "uk_directory_sync_status_organization"}, because {@code record} checked for an
 * existing row and inserted a new one without handling the unique-constraint race between the two.
 *
 * <p>The assertion is made on {@code record} directly rather than through {@link
 * DirectorySyncService#run}: {@code recordStatusSafely} deliberately swallows and logs any failure
 * here (see its javadoc), so a run driven end to end would report success while silently losing the
 * status line - the exact outcome this test must be able to see.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "basic"})
@TestPropertySource(
    properties = "OPAA_AUTH_BASIC_SECRET=test-only-secret-not-used-for-anything-sensitive-1234")
@Testcontainers(disabledWithoutDocker = true)
class DirectorySyncStatusRecorderRaceIntegrationTest {

  /**
   * Above 2 so the losing side of the race is hit reliably rather than only when the operating
   * system happens to schedule two threads adversarially, and below Hikari's default {@code
   * maximum-pool-size} of 10 is not required: {@code record} is deliberately not
   * {@code @Transactional} (see its javadoc), so no caller ever holds more than one connection at a
   * time and the threads cannot deadlock the pool the way #299's review found for the {@code
   * REQUIRES_NEW} construction.
   */
  private static final int CONCURRENT_RUNS = 8;

  @Autowired private DirectorySyncStatusRecorder statusRecorder;
  @Autowired private DirectorySyncStatusRepository statusRepository;

  @BeforeEach
  void cleanUp() {
    statusRepository.deleteAll();
  }

  @Test
  void concurrentFirstRunsOfTheSameOrganizationRecordExactlyOneStatusRow() throws Exception {
    UUID organizationId = Organization.DEFAULT_ID;
    Instant runAt = Instant.now();

    CountDownLatch ready = new CountDownLatch(CONCURRENT_RUNS);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_RUNS);
    try {
      List<Callable<Void>> runs =
          Stream.generate(() -> recordTask(organizationId, runAt, ready, start))
              .limit(CONCURRENT_RUNS)
              .toList();

      List<Future<Void>> futures = runs.stream().map(executor::submit).toList();
      ready.await();
      start.countDown();

      for (Future<Void> future : futures) {
        // Any DataIntegrityViolationException on uk_directory_sync_status_organization surfaces
        // here as a reproduction of #300 (see the class javadoc) - none of the calls may fail.
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdown();
    }

    List<DirectorySyncStatus> persisted = statusRepository.findAll();
    assertThat(persisted).hasSize(1);
    DirectorySyncStatus status = persisted.getFirst();
    assertThat(status.getOrganizationId()).isEqualTo(organizationId);
    assertThat(status.getLastOutcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(status.getLastRunAt()).isNotNull();
    // recordRun advances lastAppliedAt only for APPLIED - the loser of the race must go through it
    // too, not just write the row's insert-time state.
    assertThat(status.getLastAppliedAt()).isNotNull();
    assertThat(status.getLastMessage()).isEqualTo("Race");
  }

  @Test
  void subsequentRunsUpdateTheExistingRowInsteadOfInsertingASecondOne() {
    UUID organizationId = Organization.DEFAULT_ID;
    Instant firstRun = Instant.now();

    statusRecorder.record(
        organizationId, firstRun, DirectorySyncOutcome.APPLIED, "Erster Lauf", 0.1);
    statusRecorder.record(
        organizationId,
        firstRun.plusSeconds(60),
        DirectorySyncOutcome.UNREACHABLE,
        "Zweiter Lauf",
        0.0);

    List<DirectorySyncStatus> persisted = statusRepository.findAll();
    assertThat(persisted).hasSize(1);
    DirectorySyncStatus status = persisted.getFirst();
    assertThat(status.getLastOutcome()).isEqualTo(DirectorySyncOutcome.UNREACHABLE);
    assertThat(status.getLastMessage()).isEqualTo("Zweiter Lauf");
    // lastAppliedAt is the timestamp of the last run that actually changed rights, so the later
    // UNREACHABLE run must not advance it past the earlier APPLIED one.
    assertThat(status.getLastAppliedAt()).isBefore(status.getLastRunAt());
  }

  private Callable<Void> recordTask(
      UUID organizationId, Instant runAt, CountDownLatch ready, CountDownLatch start) {
    return () -> {
      ready.countDown();
      start.await();
      statusRecorder.record(organizationId, runAt, DirectorySyncOutcome.APPLIED, "Race", 0.25);
      return null;
    };
  }
}
