package io.opaa.instance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.common.DatabaseClock;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Two instances against one database (ADR-0053, Entscheidungen 3 und 4): this context is the first
 * instance; a second, lean context holding only the instance register starts against the same
 * database with this context's whole environment and one value changed. A value of the fingerprint
 * that differs keeps the second instance from starting, and the refusal names the key, but never
 * the value of a secret.
 */
@OpaaIntegrationTest
class InstanceRegisterIntegrationTest {

  private static final String JWT_SECRET = "opaa.auth.local.jwt-secret";
  private static final String DIMENSIONS = "spring.ai.vectorstore.pgvector.dimensions";

  @Autowired private CurrentInstance currentInstance;
  @Autowired private InstanceRegistry registry;
  @Autowired private ConfigurableEnvironment environment;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private Clock clock;
  @Autowired private DatabaseClock databaseClock;

  private final List<UUID> ownRows = new ArrayList<>();

  @BeforeEach
  @AfterEach
  void removeOwnRows() {
    ownRows.forEach(id -> jdbcTemplate.update("DELETE FROM backend_instances WHERE id = ?", id));
    ownRows.clear();
  }

  @Test
  void theRunningContextIsALiveInstanceOfTheRegister() {
    assertThat(registry.isAlive(currentInstance.id())).isTrue();
    assertThat(currentInstance.heartbeatCurrent()).isTrue();
    assertThat(registry.instances())
        .filteredOn(instance -> instance.id().equals(currentInstance.id()))
        .singleElement()
        .satisfies(
            instance -> {
              assertThat(instance.alive()).isTrue();
              assertThat(instance.stoppedAt()).isNull();
            });
  }

  @Test
  void aSecondInstanceWithTheSameConfigurationJoinsAndCountsAsDeadOnceItStops() {
    UUID[] second = new UUID[1];

    secondInstance(Map.of(), clock)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              second[0] = context.getBean(CurrentInstance.class).id();
              ownRows.add(second[0]);
              assertThat(registry.isAlive(second[0])).isTrue();
            });

    assertThat(registry.instances())
        .filteredOn(instance -> instance.id().equals(second[0]))
        .singleElement()
        .satisfies(
            instance -> {
              assertThat(instance.alive()).isFalse();
              assertThat(instance.stoppedAt()).isNotNull();
            });
    assertThat(registry.isAlive(currentInstance.id())).isTrue();
  }

  @Test
  void anInstanceStartedAgainAfterAStopJoinsUnderANewIdentity() {
    secondInstance(Map.of(), clock)
        .run(
            context -> {
              CurrentInstance instance = context.getBean(CurrentInstance.class);
              UUID first = instance.id();
              ownRows.add(first);

              instance.stop();
              instance.start();
              ownRows.add(instance.id());

              assertThat(instance.id()).isNotEqualTo(first);
              assertThat(registry.isAlive(first)).isFalse();
              assertThat(registry.isAlive(instance.id())).isTrue();
            });
  }

  @Test
  void aSecondInstanceWithAnotherJwtSecretDoesNotStartAndTheRefusalNamesOnlyTheKey() {
    String otherSecret = "another-secret-of-the-second-instance-0123456789";

    secondInstance(Map.of(JWT_SECRET, otherSecret), clock)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(refusal(context))
                  .contains(JWT_SECRET)
                  .contains(currentInstance.id().toString())
                  .doesNotContain(otherSecret)
                  .doesNotContain(environment.getProperty(JWT_SECRET));
            });
  }

  @Test
  void aSecondInstanceWithAnotherEmbeddingDimensionDoesNotStart() {
    String other = String.valueOf(Integer.parseInt(environment.getProperty(DIMENSIONS)) + 512);

    secondInstance(Map.of(DIMENSIONS, other), clock)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(refusal(context)).contains(DIMENSIONS + " (this instance: " + other + ")");
            });
  }

  @Test
  void aSecondInstanceInAnotherTimeZoneDoesNotStart() {
    ZoneId other =
        clock.getZone().getId().equals("America/New_York")
            ? ZoneId.of("Europe/Berlin")
            : ZoneId.of("America/New_York");

    secondInstance(Map.of(), clock.withZone(other))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(refusal(context))
                  .contains(
                      ConfigurationFingerprintReader.TIME_ZONE_KEY
                          + " (this instance: "
                          + other.getId()
                          + ")");
            });
  }

  @Test
  void aSecondInstanceOutsideTheRollingUpdateWindowDoesNotStart() {
    UUID newer = insertInstance("9.0.0", "9.0.0", Duration.ZERO);

    secondInstance(Map.of(), clock)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(refusal(context))
                  .contains("may not run next to version 9.0.0")
                  .contains(newer.toString());
            });
  }

  @Test
  void aDeadInstanceNoLongerBindsAStartingOne() {
    // Version 9.0.0 would refuse every start, but its heartbeat is older than dead-after.
    insertInstance("9.0.0", "9.0.0", Duration.ofMinutes(3));

    secondInstance(Map.of(), clock)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              ownRows.add(context.getBean(CurrentInstance.class).id());
            });
  }

  @Test
  void livenessFollowsTheDatabaseClockAndTheConfiguredDeadline() {
    UUID justAlive = insertInstance("0.0.0-dev", "0.0.0-dev", Duration.ofSeconds(110));
    UUID justDead = insertInstance("0.0.0-dev", "0.0.0-dev", Duration.ofSeconds(130));
    InstanceRegistry strict =
        new InstanceRegistry(
            jdbcTemplate,
            TransactionOperations.withoutTransaction(),
            new InstanceProperties(Duration.ofSeconds(15), Duration.ofSeconds(60)));

    assertThat(registry.isAlive(justAlive)).isTrue();
    assertThat(registry.isAlive(justDead)).isFalse();
    assertThat(strict.isAlive(justAlive)).isFalse();
  }

  @Test
  void anExpiredInstanceStaysDeadInsteadOfBeingRevivedByALateHeartbeat() {
    UUID expired = insertInstance("0.0.0-dev", "0.0.0-dev", Duration.ofMinutes(3));
    UUID alive = insertInstance("0.0.0-dev", "0.0.0-dev", Duration.ofSeconds(5));

    assertThat(registry.heartbeat(expired)).isFalse();
    assertThat(registry.heartbeat(alive)).isTrue();
    assertThat(registry.isAlive(expired)).isFalse();
  }

  @Test
  void aHeartbeatIsStampedByTheDatabaseClock() {
    UUID alive = insertInstance("0.0.0-dev", "0.0.0-dev", Duration.ofSeconds(5));
    var before = databaseClock.now();

    registry.heartbeat(alive);

    assertThat(registry.instances())
        .filteredOn(instance -> instance.id().equals(alive))
        .singleElement()
        .satisfies(instance -> assertThat(instance.heartbeatAt()).isAfterOrEqualTo(before));
  }

  /**
   * A row of an instance whose last heartbeat lies {@code silentFor} back by the database clock.
   */
  private UUID insertInstance(String version, String oldestCompatible, Duration silentFor) {
    UUID id = UUID.randomUUID();
    ownRows.add(id);
    jdbcTemplate.update(
        "INSERT INTO backend_instances (id, host_name, version, oldest_compatible_version,"
            + " started_at, heartbeat_at, fingerprint, fingerprint_hash) VALUES (?, 'fixture', ?,"
            + " ?, clock_timestamp() - interval '1 hour', clock_timestamp() - make_interval(secs =>"
            + " ?), '{\"spring.profiles.active\": \"x\"}', 'x')",
        id,
        version,
        oldestCompatible,
        (double) silentFor.toSeconds());
    return id;
  }

  /**
   * A second instance: the instance register alone, over this context's database and with this
   * context's whole environment, {@code overrides} on top.
   */
  private ApplicationContextRunner secondInstance(Map<String, Object> overrides, Clock ownClock) {
    DataSource shared = new DelegatingDataSource(dataSource);
    return new ApplicationContextRunner()
        .withInitializer(
            context -> {
              ConfigurableEnvironment target = context.getEnvironment();
              MutablePropertySources sources = target.getPropertySources();
              sources.forEach(source -> sources.remove(source.getName()));
              for (PropertySource<?> source : environment.getPropertySources()) {
                if (!source.getName().equals("configurationProperties")) {
                  sources.addLast(source);
                }
              }
              sources.addFirst(new MapPropertySource("second-instance", overrides));
              ConfigurationPropertySources.attach(target);
              target.setActiveProfiles(environment.getActiveProfiles());
            })
        .withBean(JdbcOperations.class, () -> new JdbcTemplate(shared))
        .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(shared))
        .withBean(Clock.class, () -> ownClock)
        .withUserConfiguration(InstanceConfiguration.class);
  }

  private static String refusal(AssertableApplicationContext context) {
    Throwable cause = context.getStartupFailure();
    while (cause != null && !(cause instanceof InstanceStartRefusedException)) {
      cause = cause.getCause();
    }
    assertThat(cause).as("the startup failure is a refusal of the register").isNotNull();
    return cause.getMessage();
  }
}
