package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ConflictException;
import io.opaa.test.FakeDirectoryClient;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Regression guard for #1711: two synchronisation runs of the same organization must not overlap,
 * while two runs of different organizations must still proceed side by side.
 *
 * <p>Needs the real database - the serialisation is a Postgres advisory lock, and a mocked
 * transaction manager or an in-process lock would prove nothing about it. {@link
 * FakeDirectoryClient#gateFetchWith} holds the first run open inside its directory fetch, which is
 * where a production run spends its time and where the lock has to be held already.
 *
 * <p>Works on two throwaway organizations rather than {@code Organization.DEFAULT_ID}, so nothing
 * here collides with a sibling class running against the shared database in a parallel test JVM.
 */
@OpaaIntegrationTest
class DirectorySyncConcurrencyIntegrationTest {

  private static final long TIMEOUT_SECONDS = 20;

  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private FakeDirectoryClient directoryClient;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID firstOrganizationId;
  private UUID secondOrganizationId;
  private ExecutorService executor;

  @BeforeEach
  void setUp() {
    firstOrganizationId = createOrganization("Abgleich-Organisation A");
    secondOrganizationId = createOrganization("Abgleich-Organisation B");
    executor = Executors.newFixedThreadPool(2);
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
    directoryClient.reset();
    removeOrganization(firstOrganizationId);
    removeOrganization(secondOrganizationId);
  }

  @Test
  void secondRunOfTheSameOrganizationIsRejectedWhileTheFirstIsStillRunning() throws Exception {
    CountDownLatch firstInsideFetch = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicBoolean gateArmed = new AtomicBoolean(true);
    directoryClient.gateFetchWith(
        organizationId -> {
          if (gateArmed.compareAndSet(true, false)) {
            firstInsideFetch.countDown();
            awaitOrFail(releaseFirst, "the first run was never released");
          }
        });

    Future<SyncReport> first = executor.submit(() -> directorySyncService.run(firstOrganizationId));
    assertThat(firstInsideFetch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .as("the first run reached the directory fetch")
        .isTrue();

    assertThatThrownBy(() -> directorySyncService.run(firstOrganizationId))
        .isInstanceOf(ConflictException.class)
        .hasMessage("Für diese Organisation läuft bereits ein Abgleich.");

    releaseFirst.countDown();
    assertThat(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).outcome()).isNotNull();
  }

  /** The dry run writes the same status row, so it takes the same lock. */
  @Test
  void dryRunOfTheSameOrganizationIsRejectedWhileARunIsStillRunning() throws Exception {
    CountDownLatch firstInsideFetch = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicBoolean gateArmed = new AtomicBoolean(true);
    directoryClient.gateFetchWith(
        organizationId -> {
          if (gateArmed.compareAndSet(true, false)) {
            firstInsideFetch.countDown();
            awaitOrFail(releaseFirst, "the first run was never released");
          }
        });

    Future<SyncReport> first = executor.submit(() -> directorySyncService.run(firstOrganizationId));
    assertThat(firstInsideFetch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> directorySyncService.dryRun(firstOrganizationId))
        .isInstanceOf(ConflictException.class);

    releaseFirst.countDown();
    first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /**
   * Fails if the lock is ever keyed on anything coarser than the organization: both runs are held
   * inside their fetch until the other has arrived there too, so neither can complete unless both
   * hold their lock at the same time.
   */
  @Test
  void runsOfDifferentOrganizationsDoNotWaitForEachOther() throws Exception {
    CountDownLatch bothInsideFetch = new CountDownLatch(2);
    directoryClient.gateFetchWith(
        organizationId -> {
          bothInsideFetch.countDown();
          awaitOrFail(bothInsideFetch, "the other organization's run never reached its fetch");
        });

    Future<SyncReport> first = executor.submit(() -> directorySyncService.run(firstOrganizationId));
    Future<SyncReport> second =
        executor.submit(() -> directorySyncService.run(secondOrganizationId));

    assertThatCode(
            () -> {
              first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
              second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            })
        .doesNotThrowAnyException();
  }

  private static void awaitOrFail(CountDownLatch latch, String message) {
    try {
      if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException(message);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(message, e);
    }
  }

  private UUID createOrganization(String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO organizations (id, name, created_at) VALUES (?, ?, now())", id, name);
    return id;
  }

  // directory_sync_status references its organization and is not one of the tables
  // OwnOrganizationFixtures covers, so it has to go before the organization row itself.
  private void removeOrganization(UUID organizationId) {
    jdbcTemplate.update(
        "DELETE FROM directory_sync_status WHERE organization_id = ?", organizationId);
    ownOrganizationFixtures.removeOrganizations(organizationId);
  }
}
