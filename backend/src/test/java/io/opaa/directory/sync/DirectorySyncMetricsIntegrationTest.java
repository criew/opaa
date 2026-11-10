package io.opaa.directory.sync;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.auth.OidcClaimMapping;
import io.opaa.auth.OidcProvider;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.organization.Organization;
import io.opaa.test.FakeDirectoryClient;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The directory synchronisation metrics against the real tables: runs count by provider type and
 * outcome, {@code last_success} is the last applied run, and a plan waiting for a decision shows in
 * {@code plan.pending.age} while {@code last_success} stands still.
 */
@OpaaIntegrationTest
class DirectorySyncMetricsIntegrationTest {

  /** Ahead of any applied run another test class records. */
  private static final Duration AHEAD = Duration.ofDays(365);

  /** Behind any plan another test class leaves waiting. */
  private static final Duration BEHIND = Duration.ofDays(400);

  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private DirectorySyncMetrics metrics;
  @Autowired private DirectorySyncStatusRecorder statusRecorder;
  @Autowired private DirectorySyncStatusRepository statusRepository;
  @Autowired private DirectorySyncPendingPlanRepository pendingPlanRepository;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private FakeDirectoryClient directoryClient;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID providerId;

  @BeforeEach
  void createProviderWithAccess() {
    OidcProvider provider =
        new OidcProvider(
            "Metriken " + UUID.randomUUID(),
            "https://idp.example/realms/" + UUID.randomUUID(),
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    provider.configureDirectorySync(true, 360);
    providerId = providerRepository.save(provider).getId();
    jdbcTemplate.update(
        "INSERT INTO directory_connectors (id, organization_id, provider_id, connector_type,"
            + " client_id, client_secret, created_at, updated_at)"
            + " VALUES (?, ?, ?, 'KEYCLOAK', 'opaa-directory', 'enc:placeholder', now(), now())",
        UUID.randomUUID(),
        Organization.DEFAULT_ID,
        providerId);
    directoryClient.respondWith();
  }

  @AfterEach
  void removeProvider() {
    directoryClient.reset();
    pendingPlanRepository
        .findByOrganizationIdAndProviderId(Organization.DEFAULT_ID, providerId)
        .ifPresent(pendingPlanRepository::delete);
    statusRepository
        .findByOrganizationIdAndProviderId(Organization.DEFAULT_ID, providerId)
        .ifPresent(statusRepository::delete);
    jdbcTemplate.update("DELETE FROM directory_connectors WHERE provider_id = ?", providerId);
    providerRepository.deleteById(providerId);
  }

  private double runs(String result) {
    Counter counter =
        meterRegistry
            .find("opaa.directory_sync.runs")
            .tags("provider", "keycloak", "result", result)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private double gauge(String name) {
    metrics.refresh();
    return meterRegistry.get(name).tag("provider", "keycloak").gauge().value();
  }

  @Test
  void anAppliedRunCountsWithItsProviderTypeAndOutcomeAndItsDuration() {
    double before = runs("applied");

    directorySyncService.run(Organization.DEFAULT_ID, providerId);

    assertThat(runs("applied")).isEqualTo(before + 1);
    Timer duration =
        meterRegistry
            .get("opaa.directory_sync.duration")
            .tags("provider", "keycloak", "result", "applied")
            .timer();
    assertThat(duration.count()).isPositive();
  }

  @Test
  void anUnreachableDirectoryCountsAsUnreachable() {
    double before = runs("unreachable");
    directoryClient.failWith("Verzeichnis nicht erreichbar");

    directorySyncService.run(Organization.DEFAULT_ID, providerId);

    assertThat(runs("unreachable")).isEqualTo(before + 1);
  }

  /**
   * The gauges keep the largest value of every provider of a type: this class's applied run lies
   * ahead of any other class's and its plan far behind, so another provider sharing the database
   * never decides the value read here.
   */
  @Test
  void aWaitingPlanShowsItsAgeWhileLastSuccessStaysAtTheLastAppliedRun() {
    Instant applied = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(AHEAD);
    statusRecorder.record(
        Organization.DEFAULT_ID, providerId, applied, DirectorySyncOutcome.APPLIED, "ok", 0.0);
    assertThat(gauge("opaa.directory_sync.last_success"))
        .isEqualTo((double) applied.getEpochSecond());

    Instant planned = Instant.now().minus(BEHIND);
    statusRecorder.record(
        Organization.DEFAULT_ID,
        providerId,
        applied.plus(Duration.ofMinutes(1)),
        DirectorySyncOutcome.PENDING_CONFIRMATION,
        "wartet",
        0.6);
    pendingPlanRepository.save(
        new DirectorySyncPendingPlan(
            Organization.DEFAULT_ID, providerId, planned, "f".repeat(64), 0.6, 3, 0, "{}"));

    assertThat(gauge("opaa.directory_sync.plan.pending.age"))
        .isBetween(
            (double) BEHIND.toSeconds() - 5, (double) BEHIND.plus(Duration.ofHours(1)).toSeconds());
    assertThat(gauge("opaa.directory_sync.last_success"))
        .as("a run that left a plan waiting is no applied run")
        .isEqualTo((double) applied.getEpochSecond());
  }

  @Test
  void aDecidedPlanNoLongerCountsTowardsThePendingAge() {
    pendingPlanRepository.save(
        new DirectorySyncPendingPlan(
            Organization.DEFAULT_ID,
            providerId,
            Instant.now().minus(BEHIND),
            "f".repeat(64),
            0.6,
            3,
            0,
            "{}"));
    assertThat(gauge("opaa.directory_sync.plan.pending.age"))
        .isGreaterThanOrEqualTo((double) BEHIND.toSeconds() - 5);

    pendingPlanRepository
        .findByOrganizationIdAndProviderId(Organization.DEFAULT_ID, providerId)
        .ifPresent(pendingPlanRepository::delete);

    assertThat(gauge("opaa.directory_sync.plan.pending.age"))
        .isLessThan((double) BEHIND.minus(Duration.ofDays(1)).toSeconds());
  }
}
