package io.opaa.directory.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.auth.OidcClaimMapping;
import io.opaa.auth.OidcProvider;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.test.FakeDirectoryClient;
import io.opaa.test.ForeignCommit;
import io.opaa.test.OpaaIntegrationTest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A directory run whose transaction meets a group an administrator changed after the run read it
 * does not fail: the version check rolls the attempt back and the run applies again against the row
 * as it is now, keeping the administrator's change. The interleaving is fixed: the change commits
 * right after the run read the provider's groups.
 */
@OpaaIntegrationTest
class DirectorySyncStaleRowIntegrationTest {

  private static final String DIRECTORY_GUID = "stale-row-guid";

  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private FakeDirectoryClient directoryClient;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;

  private UUID providerId;

  @BeforeEach
  void setUp() {
    OidcProvider provider =
        new OidcProvider(
            "Verzeichnis " + UUID.randomUUID(),
            "https://idp.example/realms/stale-" + UUID.randomUUID(),
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    provider.configureDirectorySync(true, 360);
    providerId = providerRepository.saveAndFlush(provider).getId();
  }

  @AfterEach
  void tearDown() {
    directoryClient.reset();
    jdbc.update("DELETE FROM directory_sync_status WHERE provider_id = ?", providerId);
    jdbc.update("DELETE FROM directory_sync_pending_plans WHERE provider_id = ?", providerId);
    jdbc.update("DELETE FROM groups WHERE provider_id = ?", providerId);
    jdbc.update("DELETE FROM oidc_providers WHERE id = ?", providerId);
  }

  @Test
  void aRunAppliesAgainWhenAnAdministratorChangedOneOfItsGroupsDuringTheRun() {
    directoryClient.respondWith(
        new DirectoryGroup(DIRECTORY_GUID, "Referat 10", null, null, Set.of()));
    assertThat(directorySyncService.run(Organization.DEFAULT_ID, providerId).outcome())
        .isEqualTo(DirectorySyncOutcome.APPLIED);
    UUID groupId =
        jdbc.queryForObject(
            "SELECT id FROM groups WHERE provider_id = ? AND external_id = ?",
            UUID.class,
            providerId,
            DIRECTORY_GUID);

    // the context's spy delegates to the repository proxy through its default answer
    Answer<?> real =
        Mockito.mockingDetails(userRepository).getMockCreationSettings().getDefaultAnswer();
    AtomicInteger reads = new AtomicInteger();
    doAnswer(
            invocation -> {
              Object states = real.answer(invocation);
              if (reads.incrementAndGet() == 1) {
                ForeignCommit.commit(
                    dataSource,
                    "UPDATE groups SET description = 'von der Verwaltung',"
                        + " version = version + 1 WHERE id = ?",
                    groupId);
              }
              return states;
            })
        .when(userRepository)
        .findAccountStatesOf(any(), any());
    directoryClient.respondWith(
        new DirectoryGroup(DIRECTORY_GUID, "Referat 11", null, null, Set.of()));

    SyncReport report = directorySyncService.run(Organization.DEFAULT_ID, providerId);

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(reads).as("the run applied a second time").hasValue(2);
    Map<String, Object> row =
        jdbc.queryForMap("SELECT name, description FROM groups WHERE id = ?", groupId);
    assertThat(row.get("name")).isEqualTo("Referat 11");
    assertThat(row.get("description")).isEqualTo("von der Verwaltung");
  }
}
