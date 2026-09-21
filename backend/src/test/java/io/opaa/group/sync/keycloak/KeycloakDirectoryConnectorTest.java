package io.opaa.group.sync.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.group.sync.DirectoryGroup;
import io.opaa.group.sync.DirectorySnapshot;
import io.opaa.group.sync.DirectoryUnavailableException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Keycloak connector against {@link FakeKeycloakServer} - the contract level: what the admin
 * API's answers become, how the hierarchy is walked, how the pages are joined, and that every
 * failure ends as {@link DirectoryUnavailableException} rather than as an empty directory.
 */
class KeycloakDirectoryConnectorTest {

  private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

  private FakeKeycloakServer keycloak;

  @BeforeEach
  void setUp() throws IOException {
    keycloak = new FakeKeycloakServer();
  }

  @AfterEach
  void tearDown() {
    keycloak.close();
  }

  private KeycloakDirectoryConnector connector(int pageSize, int maxGroups, int maxMembers) {
    return new KeycloakDirectoryConnector(
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        new KeycloakDirectoryProperties(
            pageSize, maxGroups, maxMembers, Duration.ofSeconds(5), Duration.ofSeconds(10)),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private KeycloakDirectoryConnector connector() {
    return connector(100, 5000, 20000);
  }

  private DirectorySnapshot fetch(KeycloakDirectoryConnector connector)
      throws DirectoryUnavailableException {
    return connector.fetchGroups(
        KeycloakRealmAddress.of(keycloak.issuerUri(), null),
        FakeKeycloakServer.CLIENT_ID,
        FakeKeycloakServer.CLIENT_SECRET);
  }

  private static DirectoryGroup groupOf(DirectorySnapshot snapshot, String externalId) {
    return snapshot.groups().stream()
        .filter(group -> group.externalId().equals(externalId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no group " + externalId));
  }

  @Test
  void aGroupTreeArrivesWithStableIdsPathsAndParents() throws Exception {
    keycloak
        .withGroup("g-haus", "Haus", "/Haus", null)
        .withGroup("g-50", "Referat 50", "/Haus/Referat 50", "g-haus", "u-1", "u-2");

    DirectorySnapshot snapshot = fetch(connector());

    assertThat(snapshot.fetchedAt()).isEqualTo(NOW);
    assertThat(snapshot.groups()).hasSize(2);
    DirectoryGroup haus = groupOf(snapshot, "g-haus");
    assertThat(haus.name()).isEqualTo("Haus");
    assertThat(haus.sourcePath()).isEqualTo("/Haus");
    assertThat(haus.parentExternalId()).isNull();
    DirectoryGroup referat = groupOf(snapshot, "g-50");
    assertThat(referat.parentExternalId()).isEqualTo("g-haus");
    assertThat(referat.sourcePath()).isEqualTo("/Haus/Referat 50");
    assertThat(referat.memberSubjects()).containsExactlyInAnyOrder("u-1", "u-2");
  }

  /**
   * ADR-0036, Entscheidung 3: a department that only holds subgroups is an <b>empty</b> group, not
   * the union of its children - Keycloak's /members reports direct members only, and OPAA's own
   * model does not inherit membership either.
   */
  @Test
  void aDepartmentWithOnlySubgroupsStaysEmpty() throws Exception {
    keycloak
        .withGroup("g-haus", "Haus", "/Haus", null)
        .withGroup("g-50", "Referat 50", "/Haus/Referat 50", "g-haus", "u-1", "u-2");

    DirectorySnapshot snapshot = fetch(connector());

    assertThat(groupOf(snapshot, "g-haus").memberSubjects()).isEmpty();
  }

  /** Three levels deep, so a single /children round is demonstrably not enough. */
  @Test
  void theHierarchyIsWalkedToItsLeaves() throws Exception {
    keycloak
        .withGroup("g-1", "Haus", "/Haus", null)
        .withGroup("g-2", "Amt 5", "/Haus/Amt 5", "g-1")
        .withGroup("g-3", "Referat 50", "/Haus/Amt 5/Referat 50", "g-2", "u-1");

    DirectorySnapshot snapshot = fetch(connector());

    assertThat(snapshot.groups())
        .extracting(DirectoryGroup::externalId)
        .containsExactlyInAnyOrder("g-1", "g-2", "g-3");
    assertThat(groupOf(snapshot, "g-3").memberSubjects()).containsExactly("u-1");
  }

  @Test
  void groupsBeyondOnePageAreFetched() throws Exception {
    for (int i = 0; i < 7; i++) {
      keycloak.withGroup("g-" + i, "Gruppe " + i, "/Gruppe " + i, null);
    }

    DirectorySnapshot snapshot = fetch(connector(3, 5000, 20000));

    assertThat(snapshot.groups()).hasSize(7);
    assertThat(keycloak.requestedPaths())
        .filteredOn(path -> path.startsWith("/admin/realms/haus/groups?"))
        .hasSize(3);
  }

  @Test
  void membersBeyondOnePageAreFetched() throws Exception {
    keycloak.withGroupOfManyMembers("g-1", "Gross", "/Gross", null, 250);

    DirectorySnapshot snapshot = fetch(connector(100, 5000, 20000));

    assertThat(groupOf(snapshot, "g-1").memberSubjects()).hasSize(250);
  }

  /** One sign-in serves the whole traversal while its token is still valid. */
  @Test
  void theServiceAccountSignsInOnce() throws Exception {
    keycloak
        .withGroup("g-1", "Haus", "/Haus", null)
        .withGroup("g-2", "Amt", "/Haus/Amt", "g-1", "u-1");

    fetch(connector());

    assertThat(keycloak.tokenRequests()).isEqualTo(1);
  }

  /** A realm larger than the token's life must not fail halfway through. */
  @Test
  void anExpiringTokenIsRenewedDuringTheTraversal() throws Exception {
    keycloak.withTokenLifetime(0);
    for (int i = 0; i < 3; i++) {
      keycloak.withGroup("g-" + i, "Gruppe " + i, "/Gruppe " + i, null);
    }

    DirectorySnapshot snapshot = fetch(connector());

    assertThat(snapshot.groups()).hasSize(3);
    assertThat(keycloak.tokenRequests()).isGreaterThan(1);
  }

  @Test
  void aRejectedSignInIsUnreachableNotAnEmptyDirectory() {
    keycloak.withGroup("g-1", "Haus", "/Haus", null);

    assertThatThrownBy(
            () ->
                connector()
                    .fetchGroups(
                        KeycloakRealmAddress.of(keycloak.issuerUri(), null),
                        FakeKeycloakServer.CLIENT_ID,
                        "falsch"))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("Dienstkonto");
  }

  /** A service account without view-users/query-groups answers 403 - never "zero groups". */
  @Test
  void missingRightsAreUnreachableNotAnEmptyDirectory() {
    keycloak.withGroup("g-1", "Haus", "/Haus", null).failingAdminCallsWith(403);

    assertThatThrownBy(() -> fetch(connector()))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("view-users");
  }

  @Test
  void anUnknownRealmIsNamedInTheMessage() {
    keycloak.withGroup("g-1", "Haus", "/Haus", null).failingAdminCallsWith(404);

    assertThatThrownBy(() -> fetch(connector()))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("haus");
  }

  @Test
  void aMalformedAnswerIsUnreachable() {
    keycloak.answeringGroupsWith("{\"not\":\"an array\"}");

    assertThatThrownBy(() -> fetch(connector()))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("keine Liste");
  }

  @Test
  void anUnreachableAddressIsUnreachable() {
    keycloak.close();

    assertThatThrownBy(() -> fetch(connector()))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("nicht erreichbar");
  }

  /**
   * A truncated group list is indistinguishable from a reorganisation that dissolved everything
   * beyond it, so the ceiling aborts the run instead of returning what was read.
   */
  @Test
  void moreGroupsThanTheCeilingAbortTheRun() {
    for (int i = 0; i < 5; i++) {
      keycloak.withGroup("g-" + i, "Gruppe " + i, "/Gruppe " + i, null);
    }

    assertThatThrownBy(() -> fetch(connector(100, 3, 20000)))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("abgeschnittene Liste");
  }

  @Test
  void moreMembersThanTheCeilingAbortTheRun() {
    keycloak.withGroupOfManyMembers("g-1", "Gross", "/Gross", null, 20);

    assertThatThrownBy(() -> fetch(connector(100, 5000, 5)))
        .isInstanceOf(DirectoryUnavailableException.class)
        .hasMessageContaining("abgeschnittene Mitgliederliste");
  }

  @Test
  void theProbeNamesTheRealmAndTheNumberOfGroups() {
    keycloak.withGroup("g-1", "Haus", "/Haus", null).withGroup("g-2", "Amt", "/Haus/Amt", "g-1");

    KeycloakDirectoryConnector.ProbeOutcome outcome =
        connector()
            .probe(
                KeycloakRealmAddress.of(keycloak.issuerUri(), null),
                FakeKeycloakServer.CLIENT_ID,
                FakeKeycloakServer.CLIENT_SECRET);

    assertThat(outcome.success()).isTrue();
    assertThat(outcome.message()).contains("haus").contains("2 Gruppen");
  }

  @Test
  void theProbeReportsAWrongSecretInGerman() {
    KeycloakDirectoryConnector.ProbeOutcome outcome =
        connector()
            .probe(
                KeycloakRealmAddress.of(keycloak.issuerUri(), null),
                FakeKeycloakServer.CLIENT_ID,
                "falsch");

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("Dienstkonto").contains("query-groups");
  }

  /**
   * The sign-in sends the secret as a form body: an unescaped value containing "&" or "=" would
   * arrive at the provider as a different secret, and the run would report a wrong credential.
   */
  @Test
  void aSecretWithReservedCharactersSurvivesTheSignIn() throws Exception {
    keycloak.expectingSecret("a&b=c").withGroup("g-1", "Haus", "/Haus", null);

    DirectorySnapshot snapshot =
        connector()
            .fetchGroups(
                KeycloakRealmAddress.of(keycloak.issuerUri(), null),
                FakeKeycloakServer.CLIENT_ID,
                "a&b=c");

    assertThat(snapshot.groups()).hasSize(1);
  }
}
