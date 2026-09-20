package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.ConflictException;
import io.opaa.organization.Organization;
import io.opaa.test.FakeDirectoryClient;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Regression guard for #1711, keyed on the provider since #1816: two synchronisation runs of the
 * same identity provider must not overlap, while two runs of different providers must still proceed
 * side by side.
 *
 * <p>Needs the real database - the serialisation is a Postgres advisory lock, and a mocked
 * transaction manager or an in-process lock would prove nothing about it. {@link
 * FakeDirectoryClient#gateFetchWith} holds the first run open inside its directory fetch, which is
 * where a production run spends its time and where the lock has to be held already.
 */
@OpaaIntegrationTest
class DirectorySyncConcurrencyIntegrationTest {

  private static final long TIMEOUT_SECONDS = 20;

  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private FakeDirectoryClient directoryClient;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private DirectorySyncStatusRepository statusRepository;
  @Autowired private DirectorySyncPendingPlanRepository pendingPlanRepository;

  private final List<UUID> createdProviderIds = new ArrayList<>();
  private UUID firstProviderId;
  private UUID secondProviderId;
  private ExecutorService executor;

  @BeforeEach
  void setUp() {
    cleanUp();
    firstProviderId = createSyncProvider();
    secondProviderId = createSyncProvider();
    executor = Executors.newFixedThreadPool(2);
  }

  // Waits for the background runs before deleting: a method that failed before releasing its run
  // leaves one in flight, and a status row written between the two deletes below would make the
  // provider's own delete fail on its foreign key and leave the throwaway row behind.
  @AfterEach
  void tearDown() throws InterruptedException {
    executor.shutdownNow();
    assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .as("the background runs finished before the cleanup")
        .isTrue();
    directoryClient.reset();
    cleanUp();
  }

  private void cleanUp() {
    createdProviderIds.forEach(
        providerId -> {
          pendingPlanRepository
              .findByOrganizationIdAndProviderId(Organization.DEFAULT_ID, providerId)
              .ifPresent(pendingPlanRepository::delete);
          statusRepository
              .findByOrganizationIdAndProviderId(Organization.DEFAULT_ID, providerId)
              .ifPresent(statusRepository::delete);
          providerRepository.deleteById(providerId);
        });
    createdProviderIds.clear();
  }

  private UUID createSyncProvider() {
    OidcProvider provider =
        new OidcProvider(
            "Verzeichnis " + UUID.randomUUID(),
            "https://idp.example/realms/" + UUID.randomUUID(),
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    provider.configureDirectorySync(true, 360);
    providerRepository.save(provider);
    createdProviderIds.add(provider.getId());
    return provider.getId();
  }

  @Test
  void secondRunOfTheSameProviderIsRejectedWhileTheFirstIsStillRunning() throws Exception {
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

    Future<SyncReport> first =
        executor.submit(() -> directorySyncService.run(Organization.DEFAULT_ID, firstProviderId));
    assertThat(firstInsideFetch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .as("the first run reached the directory fetch")
        .isTrue();

    Throwable thrown =
        catchThrowable(() -> directorySyncService.run(Organization.DEFAULT_ID, firstProviderId));
    assertThat(thrown)
        .isInstanceOf(ConflictException.class)
        .hasMessage("Für diesen Anbieter läuft bereits ein Abgleich.");
    assertThat(((ConflictException) thrown).getCode()).isEqualTo("DIRECTORY_SYNC_ALREADY_RUNNING");

    releaseFirst.countDown();
    assertThat(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).outcome()).isNotNull();
  }

  /** The dry run writes the same status row, so it takes the same lock. */
  @Test
  void dryRunOfTheSameProviderIsRejectedWhileARunIsStillRunning() throws Exception {
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

    Future<SyncReport> first =
        executor.submit(() -> directorySyncService.run(Organization.DEFAULT_ID, firstProviderId));
    assertThat(firstInsideFetch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> directorySyncService.dryRun(Organization.DEFAULT_ID, firstProviderId))
        .isInstanceOf(ConflictException.class);

    releaseFirst.countDown();
    first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /**
   * Fails if the lock is ever keyed on anything coarser than the provider: both runs are held
   * inside their fetch until the other has arrived there too, so neither can complete unless both
   * hold their lock at the same time.
   */
  @Test
  void runsOfDifferentProvidersDoNotWaitForEachOther() throws Exception {
    CountDownLatch bothInsideFetch = new CountDownLatch(2);
    directoryClient.gateFetchWith(
        organizationId -> {
          bothInsideFetch.countDown();
          awaitOrFail(bothInsideFetch, "the other provider's run never reached its fetch");
        });

    Future<SyncReport> first =
        executor.submit(() -> directorySyncService.run(Organization.DEFAULT_ID, firstProviderId));
    Future<SyncReport> second =
        executor.submit(() -> directorySyncService.run(Organization.DEFAULT_ID, secondProviderId));

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
}
