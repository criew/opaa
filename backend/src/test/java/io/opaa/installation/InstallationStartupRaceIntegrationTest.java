package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.account.LocalAdminReset;
import io.opaa.auth.AuthProperties;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.OidcProviderRegistry;
import io.opaa.ratelimit.RateLimitProperties;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import io.opaa.test.TwoInstanceRace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two instances start together with the same answer file (ADR-0053, ADR-0060): both wait behind the
 * row of {@code installation_state}; one completes the installation, the other finds it completed
 * and starts as well - the start of neither fails.
 */
@OpaaLocalAuthMockMvcTest
class InstallationStartupRaceIntegrationTest {

  private static final String LOCK_ROW =
      "SELECT id FROM installation_state WHERE id = ? FOR UPDATE";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private InstallationService installation;
  @Autowired private DatabaseInstallationStatus status;
  @Autowired private LocalAccountFixturesFactory accountsFactory;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private AuthProperties authProperties;
  @Autowired private LocalAdminReset adminReset;
  @Autowired private OidcProviderRegistry registry;
  @Autowired private JsonMapper json;
  @Autowired private RateLimitProperties rateLimit;

  private InstallationFixture fixture;
  private Path directory;

  @BeforeEach
  void setUp() throws Exception {
    fixture = new InstallationFixture(jdbc, installation, status, accountsFactory.create());
    directory = Files.createTempDirectory("opaa-installation-race");
  }

  @AfterEach
  void tearDown() {
    fixture.close();
  }

  // regression guard: the instance that loses the race for the answer file still starts
  @Test
  void twoInstancesApplyingTheSameAnswerFileBothStartAndCompleteOnce() throws Exception {
    Path password =
        Files.writeString(directory.resolve("password"), InstallationFixture.ADMIN_PASSWORD);
    Path answers =
        Files.writeString(
            directory.resolve("installation.json"),
            "{\"firstAdmin\":{\"email\":\""
                + InstallationFixture.ADMIN_EMAIL
                + "\",\"displayName\":\"IT\",\"passwordFile\":\""
                + password
                + "\"},\"signIn\":{\"mode\":\"LOCAL_ONLY\"},\"chatModel\":{\"baseUrl\":\""
                + fixture.chatBaseUrl()
                + "\",\"model\":\"qwen2.5:7b\"}}");
    List<Boolean> outcomes = new CopyOnWriteArrayList<>();
    Runnable start = () -> outcomes.add(startup(answers).applyAnswerFile());

    new TwoInstanceRace(dataSource).run(start, start, LOCK_ROW, (short) 1);

    assertThat(outcomes).containsExactly(true, true);
    assertThat(credentials.findAll().stream().filter(row -> row.isBootstrap())).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_type = 'INSTALLATION_COMPLETED'"
                    + " AND actor_ref = ?",
                Integer.class,
                InstallationService.SYSTEM_ACTOR))
        .isEqualTo(1);
  }

  private InstallationStartup startup(Path answers) {
    return new InstallationStartup(
        installation,
        new InstallationProperties(null, List.of(), answers.toString()),
        authProperties,
        status,
        adminReset,
        registry,
        json,
        rateLimit);
  }
}
