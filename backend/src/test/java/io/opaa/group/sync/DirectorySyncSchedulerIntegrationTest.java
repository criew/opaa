package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.api.types.GroupKind;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.organization.Organization;
import io.opaa.test.FakeDirectoryClient;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The schedule of #1816: a run starts without anyone triggering it. The tick is called here rather
 * than waited for - {@code opaa.directory-sync.schedule-enabled} is off in this context (see {@link
 * OpaaIntegrationTest}), so no run ever starts behind another class's back, and a test that waited
 * for the cron would prove the clock rather than the rule.
 */
@OpaaIntegrationTest
class DirectorySyncSchedulerIntegrationTest {

  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private DirectorySyncStatusRepository statusRepository;
  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private FakeDirectoryClient directoryClient;

  private static final UUID ORGANIZATION_ID = Organization.DEFAULT_ID;

  private final List<UUID> createdProviderIds = new ArrayList<>();
  private DirectorySyncScheduler scheduler;

  @BeforeEach
  void setUp() {
    cleanUp();
    scheduler =
        new DirectorySyncScheduler(providerRepository, statusRepository, directorySyncService);
    directoryClient.respondWith();
  }

  @AfterEach
  void tearDown() {
    cleanUp();
  }

  // Scoped to this class's own providers: whatever a tick created belongs to one of them, and
  // fk_groups_provider is RESTRICT, so the groups go before the provider row.
  private void cleanUp() {
    createdProviderIds.forEach(
        providerId -> {
          groupRepository.deleteAll(groupRepository.findByProviderId(providerId));
          statusRepository
              .findByOrganizationIdAndProviderId(ORGANIZATION_ID, providerId)
              .ifPresent(statusRepository::delete);
          providerRepository.deleteById(providerId);
        });
    createdProviderIds.clear();
  }

  /** A provider that has never run is due at once - switching the run on needs no second handle. */
  @Test
  void aProviderThatHasNeverRunIsDueAtOnce() {
    OidcProvider provider = createProvider(true, true, 360);
    directoryClient.respondWithFor(
        provider.getId(), new DirectoryGroup("dir-1", "Referat 50", null, Set.of()));

    scheduler.triggerDueProviders();

    assertThat(
            statusRepository
                .findByOrganizationIdAndProviderId(ORGANIZATION_ID, provider.getId())
                .orElseThrow()
                .getLastOutcome())
        .isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(groupsOf(provider.getId())).hasSize(1);
  }

  @Test
  void aProviderWhoseLastRunIsNewerThanItsIntervalIsNotDueYet() {
    OidcProvider provider = createProvider(true, true, 360);
    DirectorySyncStatus status = new DirectorySyncStatus(ORGANIZATION_ID, provider.getId());
    Instant lastRun = Instant.now().minusSeconds(60);
    status.recordRun(lastRun, DirectorySyncOutcome.APPLIED, "Voriger Lauf", 0.0);
    statusRepository.save(status);
    directoryClient.respondWithFor(
        provider.getId(), new DirectoryGroup("dir-1", "Referat 50", null, Set.of()));

    scheduler.triggerDueProviders();

    // Compared on the message, not on the timestamp: Postgres stores microseconds, so a
    // round-tripped Instant is never equal to the nanosecond one the fixture wrote.
    assertThat(
            statusRepository
                .findByOrganizationIdAndProviderId(ORGANIZATION_ID, provider.getId())
                .orElseThrow()
                .getLastMessage())
        .isEqualTo("Voriger Lauf");
    assertThat(groupsOf(provider.getId())).isEmpty();
  }

  @Test
  void aProviderWhoseIntervalHasElapsedRunsAgain() {
    OidcProvider provider = createProvider(true, true, 5);
    DirectorySyncStatus status = new DirectorySyncStatus(ORGANIZATION_ID, provider.getId());
    Instant lastRun = Instant.now().minusSeconds(600);
    status.recordRun(lastRun, DirectorySyncOutcome.APPLIED, "Voriger Lauf", 0.0);
    statusRepository.save(status);
    directoryClient.respondWithFor(
        provider.getId(), new DirectoryGroup("dir-1", "Referat 50", null, Set.of()));

    scheduler.triggerDueProviders();

    assertThat(
            statusRepository
                .findByOrganizationIdAndProviderId(ORGANIZATION_ID, provider.getId())
                .orElseThrow()
                .getLastRunAt())
        .isAfter(lastRun);
    assertThat(groupsOf(provider.getId())).hasSize(1);
  }

  /** The run of a disabled provider pauses (ADR-0036, Entscheidung 2) - the tick passes it by. */
  @Test
  void aDisabledProviderIsNeverTickedEvenWithItsRunSwitchedOn() {
    OidcProvider provider = createProvider(false, true, 5);
    directoryClient.respondWithFor(
        provider.getId(), new DirectoryGroup("dir-1", "Referat 50", null, Set.of()));

    scheduler.triggerDueProviders();

    assertThat(
            statusRepository.findByOrganizationIdAndProviderId(ORGANIZATION_ID, provider.getId()))
        .isEmpty();
    assertThat(groupsOf(provider.getId())).isEmpty();
  }

  @Test
  void aProviderWithoutTheRunSwitchedOnIsNeverTicked() {
    OidcProvider provider = createProvider(true, false, null);
    directoryClient.respondWithFor(
        provider.getId(), new DirectoryGroup("dir-1", "Referat 50", null, Set.of()));

    scheduler.triggerDueProviders();

    assertThat(
            statusRepository.findByOrganizationIdAndProviderId(ORGANIZATION_ID, provider.getId()))
        .isEmpty();
    assertThat(groupsOf(provider.getId())).isEmpty();
  }

  /**
   * One provider's failure must not keep the others from running - otherwise a single unreachable
   * directory would silently stop every other provider's schedule.
   */
  @Test
  void aProviderWhoseRunFailsDoesNotStopTheTick() {
    OidcProvider failing = createProvider(true, true, 5);
    OidcProvider healthy = createProvider(true, true, 5);
    // The failing one comes first in the order the tick walks, so it is reached before the other.
    failing.setSortOrder(1);
    healthy.setSortOrder(2);
    providerRepository.save(failing);
    providerRepository.save(healthy);
    directoryClient.breakFor(failing.getId());
    directoryClient.respondWithFor(
        healthy.getId(), new DirectoryGroup("dir-1", "Referat 50", null, Set.of()));

    scheduler.triggerDueProviders();

    assertThat(statusRepository.findByOrganizationIdAndProviderId(ORGANIZATION_ID, failing.getId()))
        .as("the defective provider recorded nothing")
        .isEmpty();
    assertThat(
            statusRepository
                .findByOrganizationIdAndProviderId(ORGANIZATION_ID, healthy.getId())
                .orElseThrow()
                .getLastOutcome())
        .isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(groupsOf(healthy.getId())).hasSize(1);
  }

  // ---------------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------------

  private OidcProvider createProvider(boolean enabled, boolean syncEnabled, Integer interval) {
    OidcProvider provider =
        new OidcProvider(
            "Verzeichnis " + UUID.randomUUID(),
            "https://idp.example/realms/" + UUID.randomUUID(),
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    if (syncEnabled) {
      provider.configureDirectorySync(true, interval);
    }
    if (!enabled) {
      provider.disable();
    }
    providerRepository.save(provider);
    createdProviderIds.add(provider.getId());
    return provider;
  }

  private List<Group> groupsOf(UUID providerId) {
    return groupRepository.findByProviderIdAndKind(providerId, GroupKind.ORG_UNIT);
  }
}
