package io.opaa.directory.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.auth.OidcClaimMapping;
import io.opaa.auth.OidcProvider;
import io.opaa.auth.OidcProviderRepository;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The tick shares the application's single scheduler thread with every other {@code @Scheduled}
 * method, so it must hand the run itself to its own executor: a directory fetch that takes a minute
 * would otherwise hold every other tick back for that minute.
 */
class DirectorySyncSchedulerTest {

  private final OidcProviderRepository providerRepository = mock(OidcProviderRepository.class);
  private final DirectorySyncStatusRepository statusRepository =
      mock(DirectorySyncStatusRepository.class);
  private final DirectorySyncService directorySyncService = mock(DirectorySyncService.class);

  private final CountDownLatch runStarted = new CountDownLatch(1);
  private final CountDownLatch runMayFinish = new CountDownLatch(1);
  private final CountDownLatch runFinished = new CountDownLatch(1);
  private final AtomicReference<Thread> runThread = new AtomicReference<>();

  private final DirectorySyncScheduler scheduler =
      new DirectorySyncScheduler(
          providerRepository,
          statusRepository,
          directorySyncService,
          new DirectorySyncConfiguration().directorySyncExecutor(),
          Clock.systemUTC());

  @AfterEach
  void tearDown() {
    runMayFinish.countDown();
  }

  @Test
  void theTickReturnsWhileTheRunIsStillInFlightOnAnotherThread() throws InterruptedException {
    OidcProvider provider =
        new OidcProvider(
            "Verzeichnis",
            "https://idp.example/realms/test",
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    provider.configureDirectorySync(true, 5);
    when(providerRepository
            .findByDirectorySyncEnabledTrueAndEnabledTrueOrderBySortOrderAscDisplayNameAsc())
        .thenReturn(List.of(provider));
    doAnswer(
            invocation -> {
              runThread.set(Thread.currentThread());
              runStarted.countDown();
              // Bounded: a tick that runs the sync inline would otherwise never return.
              runMayFinish.await(2, TimeUnit.SECONDS);
              runFinished.countDown();
              return null;
            })
        .when(directorySyncService)
        .runScheduled(any());

    scheduler.triggerDueProviders();

    assertThat(runStarted.await(5, TimeUnit.SECONDS)).as("the run was started").isTrue();
    assertThat(runFinished.getCount()).as("the tick returned before the run finished").isEqualTo(1);
    assertThat(runThread.get()).isNotSameAs(Thread.currentThread());
    runMayFinish.countDown();
    assertThat(runFinished.await(5, TimeUnit.SECONDS)).isTrue();
  }
}
