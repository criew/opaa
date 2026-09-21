package io.opaa.integration.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.group.sync.DirectoryGroup;
import io.opaa.group.sync.DirectorySnapshot;
import io.opaa.group.sync.DirectoryUnavailableException;
import io.opaa.group.sync.keycloak.KeycloakDirectoryConnector;
import io.opaa.group.sync.keycloak.KeycloakDirectoryProperties;
import io.opaa.group.sync.keycloak.KeycloakRealmAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The connector against the real Admin REST API: what {@code FakeKeycloakServer} asserts at the
 * contract level, re-verified against the answers Keycloak actually gives - the shapes a hand-built
 * double gets wrong most easily (top-level-only {@code /groups}, direct-only {@code /members}) and
 * the rights a service account really needs.
 */
@Testcontainers(disabledWithoutDocker = true)
class KeycloakDirectoryConnectorRealmTest {

  private static KeycloakFixture keycloak;

  @BeforeAll
  static void start() {
    keycloak = KeycloakFixture.get();
  }

  private static KeycloakDirectoryConnector connector(int pageSize) {
    return new KeycloakDirectoryConnector(
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        new KeycloakDirectoryProperties(
            pageSize, 5000, 20000, Duration.ofSeconds(5), Duration.ofSeconds(30)),
        Clock.systemUTC());
  }

  private static DirectorySnapshot fetch(int pageSize) throws DirectoryUnavailableException {
    return connector(pageSize)
        .fetchGroups(
            KeycloakRealmAddress.of(keycloak.issuerUri(), null),
            KeycloakFixture.DIRECTORY_CLIENT_ID,
            KeycloakFixture.DIRECTORY_CLIENT_SECRET);
  }

  private static DirectoryGroup groupOf(DirectorySnapshot snapshot, String externalId) {
    return snapshot.groups().stream()
        .filter(group -> group.externalId().equals(externalId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no group " + externalId));
  }

  /** The realm is derived from the issuer; nothing points the read at another one. */
  @Test
  void theWholeGroupTreeArrivesWithKeycloaksOwnIdsAndPaths() throws Exception {
    DirectorySnapshot snapshot = fetch(100);

    DirectoryGroup haus = groupOf(snapshot, keycloak.hausGroupId());
    assertThat(haus.name()).isEqualTo("Haus");
    assertThat(haus.sourcePath()).isEqualTo("/Haus");
    assertThat(haus.parentExternalId()).isNull();

    DirectoryGroup referat = groupOf(snapshot, keycloak.referat50GroupId());
    assertThat(referat.sourcePath()).isEqualTo("/Haus/Referat 50");
    assertThat(referat.parentExternalId()).isEqualTo(keycloak.hausGroupId());
    assertThat(referat.memberSubjects())
        .containsExactlyInAnyOrder(keycloak.user1Id(), keycloak.user2Id());
  }

  /**
   * ADR-0036, Entscheidung 3, verified against the real API: {@code /members} reports the direct
   * members of that one group, so a department whose people all sit in its subgroups is empty.
   */
  @Test
  void aDepartmentWithOnlySubgroupsHasNoDirectMembers() throws Exception {
    DirectorySnapshot snapshot = fetch(100);

    assertThat(groupOf(snapshot, keycloak.hausGroupId()).memberSubjects()).isEmpty();
    assertThat(groupOf(snapshot, keycloak.referat51GroupId()).memberSubjects())
        .containsExactly(keycloak.user3Id());
    assertThat(groupOf(snapshot, keycloak.externGroupId()).memberSubjects()).isEmpty();
  }

  /** A page size below the number of top-level groups must not truncate the directory. */
  @Test
  void aPageSizeBelowTheNumberOfGroupsStillReadsThemAll() throws Exception {
    DirectorySnapshot withOnePage = fetch(100);

    DirectorySnapshot paged = fetch(3);

    assertThat(paged.groups()).hasSameSizeAs(withOnePage.groups());
    assertThat(paged.groups())
        .extracting(DirectoryGroup::externalId)
        .containsAll(keycloak.paginationGroupIds());
  }

  /** Acceptance criterion of #1817: a rename in the directory does not change the identity. */
  @Test
  void aRenamedGroupKeepsItsDirectoryId() throws Exception {
    String groupId = keycloak.externGroupId();
    assertThat(groupOf(fetch(100), groupId).name()).isEqualTo("Extern");

    keycloak.renameGroup(groupId, "Externe Stellen");
    try {
      DirectoryGroup renamed = groupOf(fetch(100), groupId);

      assertThat(renamed.externalId()).isEqualTo(groupId);
      assertThat(renamed.name()).isEqualTo("Externe Stellen");
      assertThat(renamed.sourcePath()).isEqualTo("/Externe Stellen");
    } finally {
      keycloak.renameGroup(groupId, "Extern");
    }
  }

  /** A wrong secret is "unreachable" - never an empty directory, which would revoke rights. */
  @Test
  void aWrongSecretIsUnreachable() {
    assertThatThrownBy(
            () ->
                connector(100)
                    .fetchGroups(
                        KeycloakRealmAddress.of(keycloak.issuerUri(), null),
                        KeycloakFixture.DIRECTORY_CLIENT_ID,
                        "falsch"))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("Dienstkonto");
  }

  /** The two realm-management roles ADR-0036 names are really needed - and really enough. */
  @Test
  void aServiceAccountWithoutTheTwoRolesIsUnreachable() {
    assertThatThrownBy(
            () ->
                connector(100)
                    .fetchGroups(
                        KeycloakRealmAddress.of(keycloak.issuerUri(), null),
                        KeycloakFixture.POWERLESS_CLIENT_ID,
                        KeycloakFixture.POWERLESS_CLIENT_SECRET))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("view-users");
  }

  @Test
  void theProbeNamesTheRealmAndTheNumberOfGroups() {
    KeycloakDirectoryConnector.ProbeOutcome outcome =
        connector(100)
            .probe(
                KeycloakRealmAddress.of(keycloak.issuerUri(), null),
                KeycloakFixture.DIRECTORY_CLIENT_ID,
                KeycloakFixture.DIRECTORY_CLIENT_SECRET);

    assertThat(outcome.success()).isTrue();
    assertThat(outcome.message()).contains(KeycloakFixture.REALM).contains("Gruppen");
  }

  /** An unknown realm is refused by name rather than read as an empty directory. */
  @Test
  void anUnknownRealmIsUnreachable() {
    assertThatThrownBy(
            () ->
                connector(100)
                    .fetchGroups(
                        KeycloakRealmAddress.of(keycloak.baseUrl() + "/realms/gibt-es-nicht", null),
                        KeycloakFixture.DIRECTORY_CLIENT_ID,
                        KeycloakFixture.DIRECTORY_CLIENT_SECRET))
        .isInstanceOf(DirectoryUnavailableException.class);
  }
}
