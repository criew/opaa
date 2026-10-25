package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.common.NotFoundException;
import io.opaa.installation.InstallationState.CompletionSource;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import io.opaa.test.TwoInstanceRace;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two instances at the same moment against one database (ADR-0053, ADR-0060): both wait behind the
 * row of {@code installation_state}, and only one of them completes the installation - one
 * emergency account, one completion entry, the other answers {@code 404}. Two instances starting a
 * pending installation without a configured token generate and print exactly one token.
 */
@OpaaLocalAuthMockMvcTest
class InstallationTwoInstanceIntegrationTest {

  private static final String LOCK_ROW =
      "SELECT id FROM installation_state WHERE id = ? FOR UPDATE";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private InstallationService installation;
  @Autowired private DatabaseInstallationStatus status;
  @Autowired private LocalAccountFixturesFactory accountsFactory;
  @Autowired private LocalCredentialsRepository credentials;

  private InstallationFixture fixture;
  private io.opaa.test.LocalAccountFixtures accounts;

  @BeforeEach
  void setUp() throws Exception {
    accounts = accountsFactory.create();
    fixture = new InstallationFixture(jdbc, installation, status, accounts);
  }

  @AfterEach
  void tearDown() {
    fixture.close();
  }

  @Test
  void twoInstancesCompleteTheInstallationExactlyOnce() throws Exception {
    for (StepInput input : steps()) {
      installation.save(input);
      assertThat(installation.check(input.step()).checked()).isTrue();
    }
    AtomicInteger completed = new AtomicInteger();
    AtomicInteger notFound = new AtomicInteger();
    Runnable complete =
        () -> {
          try {
            installation.complete(CompletionSource.INSTALLATION_API);
            completed.incrementAndGet();
          } catch (NotFoundException e) {
            notFound.incrementAndGet();
          }
        };

    new TwoInstanceRace(dataSource).run(complete, complete, LOCK_ROW, (short) 1);

    assertThat(completed).hasValue(1);
    assertThat(notFound).hasValue(1);
    assertThat(credentials.findAll().stream().filter(row -> row.isBootstrap())).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_type = 'INSTALLATION_COMPLETED'"
                    + " AND actor_ref = ?",
                Integer.class,
                InstallationService.SYSTEM_ACTOR))
        .isEqualTo(1);
  }

  @Test
  void twoInstancesStartingAPendingInstallationGenerateOneToken() throws Exception {
    jdbc.update("UPDATE installation_state SET token_hash = NULL, token_source = NULL");
    List<String> generated = new CopyOnWriteArrayList<>();
    Runnable start =
        () -> {
          String token = installation.ensureToken(null);
          if (token != null) {
            generated.add(token);
          }
        };

    new TwoInstanceRace(dataSource).run(start, start, LOCK_ROW, (short) 1);

    assertThat(generated).hasSize(1);
    assertThat(installation.tokenMatches(generated.getFirst())).isTrue();
  }

  private List<StepInput> steps() {
    return List.of(
        new StepInput.FirstAdmin(
            InstallationFixture.ADMIN_EMAIL, "IT-Betrieb", InstallationFixture.ADMIN_PASSWORD),
        new StepInput.SignIn(StepInput.SignInMode.LOCAL_ONLY, null),
        new StepInput.ChatModel(fixture.chatBaseUrl(), "qwen2.5:7b", null, null, null));
  }

  // regression guard: a configured token stops counting once it is no longer configured
  @Test
  void aConfiguredTokenThatIsNoLongerConfiguredIsReplaced() {
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
        new ch.qos.logback.core.read.ListAppender<>();
    logs.start();
    ch.qos.logback.classic.Logger serviceLog =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(InstallationService.class);
    serviceLog.addAppender(logs);
    String generated;
    try {
      generated = installation.ensureToken(null);
    } finally {
      serviceLog.detachAppender(logs);
    }

    assertThat(logs.list)
        .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .anyMatch(message -> message.contains("on all instances with the same value"));

    assertThat(generated).isNotNull();
    assertThat(installation.tokenMatches(InstallationFixture.TOKEN)).isFalse();
    assertThat(installation.tokenMatches(generated)).isTrue();
    assertThat(installation.ensureToken(null)).isNull();
  }

  // regression guard: a database with accounts counts as in use even without the seed markers
  @Test
  void aDatabaseWithAccountsButWithoutSeedMarkersCountsAsExisting() {
    accounts.activeUser("bestand-" + java.util.UUID.randomUUID() + "@stadt.example");
    jdbc.update("DELETE FROM local_admin_seed_marker");
    jdbc.update("DELETE FROM oidc_provider_seed_marker");
    jdbc.update("DELETE FROM llm_model_seed_marker");

    InstallationService.StartupDecision decision =
        installation.completeAtStartupIfDue(false, false);

    assertThat(decision.completed()).isTrue();
    assertThat(decision.source()).isEqualTo(InstallationState.CompletionSource.EXISTING);
  }
}
