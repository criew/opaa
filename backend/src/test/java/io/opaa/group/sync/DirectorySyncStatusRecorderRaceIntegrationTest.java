package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises the race described in #300: two or more concurrent <em>first</em> synchronisation runs
 * of the same provider reaching {@link DirectorySyncStatusRecorder#record} together, before either
 * has committed that provider's single {@code directory_sync_status} row (per provider since
 * #1816). The run lock serialises the runs themselves, but the recorder is called outside it and
 * has to stand on its own.
 *
 * <p>Runs against a real Postgres instance with the real, versioned Liquibase schema ({@code
 * spring.liquibase.enabled=true}, {@code ddl-auto=none}) and real threads, following {@code
 * UserServiceCreationRaceIntegrationTest}'s pattern - a mocked transaction manager would only
 * exercise the catch block and not the propagation and visibility semantics the fix depends on,
 * which is precisely where the two previous attempts at this class of fix went wrong (#280, #297).
 *
 * <p>Before the fix, {@link #concurrentFirstRunsOfTheSameProviderRecordExactlyOneStatusRow()}
 * fails: the losers of the race throw {@link
 * org.springframework.dao.DataIntegrityViolationException} with {@code duplicate key value violates
 * unique constraint "uk_directory_sync_status_organization_provider"}, because {@code record}
 * checked for an existing row and inserted a new one without handling the unique-constraint race
 * between the two.
 *
 * <p>The assertion is made on {@code record} directly rather than through {@link
 * DirectorySyncService#run}: {@code recordStatusSafely} deliberately swallows and logs any failure
 * here (see its javadoc), so a run driven end to end would report success while silently losing the
 * status line - the exact outcome this test must be able to see.
 */
@OpaaIntegrationTest
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

  @Autowired private io.opaa.auth.oidc.OidcProviderRepository providerRepository;

  /**
   * This class's own provider. {@code directory_sync_status.provider_id} is a foreign key since
   * #1816, so the status row needs a real provider row to point at; it is created per method and
   * removed again afterwards, together with the status row it carries.
   */
  private UUID providerId;

  @BeforeEach
  void createOwnProvider() {
    io.opaa.auth.oidc.OidcProvider provider =
        new io.opaa.auth.oidc.OidcProvider(
            "Statuszeile " + UUID.randomUUID(),
            "https://idp.example/realms/" + UUID.randomUUID(),
            "opaa-frontend",
            null,
            io.opaa.auth.oidc.OidcClaimMapping.keycloakDefaults());
    providerRepository.save(provider);
    providerId = provider.getId();
  }

  @AfterEach
  void removeOwnProvider() {
    statusRepository
        .findByOrganizationIdAndProviderId(Organization.DEFAULT_ID, providerId)
        .ifPresent(status -> statusRepository.deleteById(status.getId()));
    providerRepository.deleteById(providerId);
  }

  @Test
  void concurrentFirstRunsOfTheSameProviderRecordExactlyOneStatusRow() throws Exception {
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
        // Any DataIntegrityViolationException on uk_directory_sync_status_organization_provider
        // surfaces here as a reproduction of #300 (see the class javadoc) - none may fail.
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdown();
    }

    DirectorySyncStatus status =
        statusRepository
            .findByOrganizationIdAndProviderId(organizationId, providerId)
            .orElseThrow();
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
        organizationId, providerId, firstRun, DirectorySyncOutcome.APPLIED, "Erster Lauf", 0.1);
    statusRecorder.record(
        organizationId,
        providerId,
        firstRun.plusSeconds(60),
        DirectorySyncOutcome.UNREACHABLE,
        "Zweiter Lauf",
        0.0);

    DirectorySyncStatus status =
        statusRepository
            .findByOrganizationIdAndProviderId(organizationId, providerId)
            .orElseThrow();
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
      statusRecorder.record(
          organizationId, providerId, runAt, DirectorySyncOutcome.APPLIED, "Race", 0.25);
      return null;
    };
  }
}
