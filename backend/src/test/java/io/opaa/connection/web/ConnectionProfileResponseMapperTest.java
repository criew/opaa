package io.opaa.connection.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.ConnectionProfileImpactResponse;
import io.opaa.api.dto.ConnectionProfileOption;
import io.opaa.api.dto.ConnectionProfileResponse;
import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.connection.ConnectorReleaseService.ProfileOption;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileService.Discards;
import io.opaa.connection.profile.ConnectionProfileService.ProfileImpact;
import io.opaa.connection.profile.PersonNumbers.ProfileCounts;
import io.opaa.connection.profile.TestPersonCounts;
import io.opaa.knowledge.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Pins the field-by-field mapping of a selectable profile, its connector defaults included. */
class ConnectionProfileResponseMapperTest {

  @Test
  void anOptionCarriesTheProfileAndItsConnectorDefaults() {
    ConnectionProfile profile = profile("{\"edition\": \"DC\", \"pathStyle\": true}");
    ReflectionTestUtils.setField(profile, "sourceProxy", "proxy.example.org:8080");
    ReflectionTestUtils.setField(profile, "sourceInsecureSsl", true);
    ReflectionTestUtils.setField(profile, "ownership", ConnectionOwnership.PERSON);

    ConnectionProfileOption option =
        ConnectionProfileResponseMapper.toOption(
            new ProfileOption(profile, false, "Der Zugang ist gesperrt.", true));

    assertThat(option.getId()).isEqualTo(profile.getId());
    assertThat(option.getName()).isEqualTo("Zugang Wiki");
    assertThat(option.getSourceType()).isEqualTo("PROFILE_PROBE");
    assertThat(option.getServerUrl()).isEqualTo("https://wiki.example.org");
    assertThat(option.getAuthMethod()).isEqualTo(ConnectionAuthMethod.PERSONAL_SECRET);
    assertThat(option.getOwnership()).isEqualTo(ConnectionOwnership.PERSON);
    assertThat(option.getOwnAccount()).isTrue();
    assertThat(option.getCreatable()).isFalse();
    assertThat(option.getCreationNotice()).isEqualTo("Der Zugang ist gesperrt.");
    assertThat(option.getConnectorDefaults()).isEqualTo(Map.of("edition", "DC", "pathStyle", true));
    assertThat(option.getSourceProxy()).isEqualTo("proxy.example.org:8080");
    assertThat(option.getSourceInsecureSsl()).isTrue();
  }

  @Test
  void aResponseCarriesProxyAndTlsSwitch() {
    ConnectionProfile profile = profile(null);
    ReflectionTestUtils.setField(profile, "ownership", ConnectionOwnership.LIBRARY);
    ReflectionTestUtils.setField(profile, "sourceProxy", "proxy.example.org:8080");

    ConnectionProfileResponse response =
        ConnectionProfileResponseMapper.toResponse(profile, false, 0, TestPersonCounts.of(0, 0));

    assertThat(response.getSourceProxy()).isEqualTo("proxy.example.org:8080");
    assertThat(response.getSourceInsecureSsl()).isFalse();
  }

  /**
   * A profile that admits no persons never warns about expired ones - its ownership is public, and
   * its masked numbers mean nothing; an empty profile for persons warns like any other.
   */
  @Test
  void aProfileWithoutPersonsNeverWarnsAboutExpiredConnections() {
    ConnectionProfile profile = profile(null);
    ProfileCounts admittingTheThreshold = TestPersonCounts.of(0, 12);
    assertThat(admittingTheThreshold.expiredWarning()).isTrue();

    for (ConnectionOwnership ownership : ConnectionOwnership.values()) {
      ReflectionTestUtils.setField(profile, "ownership", ownership);
      ConnectionProfileResponse response =
          ConnectionProfileResponseMapper.toResponse(profile, false, 0, admittingTheThreshold);
      assertThat(response.getExpiredConnectionWarning())
          .as("ownership %s", ownership)
          .isEqualTo(ownership.admitsPersons());
    }
  }

  /** The mapper copies the masked numbers as they are, and leaves an untold part absent. */
  @Test
  void personCountsAreExactOnlyFromTheMinimumGroupSizeOn() {
    ConnectionProfile profile = profile(null);
    ReflectionTestUtils.setField(profile, "ownership", ConnectionOwnership.PERSON);

    ConnectionProfileResponse response =
        ConnectionProfileResponseMapper.toResponse(profile, false, 2, TestPersonCounts.of(1, 5));
    ConnectionProfileResponse small =
        ConnectionProfileResponseMapper.toResponse(profile, false, 2, TestPersonCounts.of(3, 0));
    ConnectionProfileImpactResponse impact =
        ConnectionProfileResponseMapper.toResponse(
            new ProfileImpact(
                2, 2, TestPersonCounts.of(0, 0).total(), List.of(), null, Discards.NONE),
            false);

    assertThat(response.getConnectionCount()).isEqualTo(2);
    assertThat(response.getConnectedAccountCount().getCount()).isEqualTo(6);
    assertThat(response.getConnectedAccountCount().getFewerThan()).isNull();
    assertThat(response.getExpiredConnectionCount()).isNull();
    assertThat(small.getConnectedAccountCount().getCount()).isNull();
    assertThat(small.getConnectedAccountCount().getFewerThan()).isEqualTo(5);
    assertThat(small.getExpiredConnectionCount().getFewerThan()).isEqualTo(5);
    assertThat(impact.getConnectedAccounts().getCount()).isNull();
    assertThat(impact.getConnectedAccounts().getFewerThan()).isEqualTo(5);
    assertThat(impact.getConnections()).isEqualTo(2);
    assertThat(response.getExpiredConnectionWarning()).isFalse();
    assertThat(
            ConnectionProfileResponseMapper.toResponse(
                    profile, false, 2, TestPersonCounts.of(6, 12))
                .getExpiredConnectionWarning())
        .isTrue();
  }

  @Test
  void theImpactCarriesWhatSavingDiscardsAndItsConfirmation() {
    Discards rebound =
        new Discards(0, 2, 3, TestPersonCounts.of(1, 0).total(), List.of("Imitiertes Konto"), 3);
    Discards moved = new Discards(3, 2, 3, TestPersonCounts.of(1, 0).total(), List.of(), 0);

    ConnectionProfileImpactResponse impact = impactWith(rebound);
    ConnectionProfileImpactResponse address = impactWith(moved);
    ConnectionProfileImpactResponse none = impactWith(Discards.NONE);

    assertThat(impact.getConnectionsDiscarded()).isZero();
    assertThat(impact.getSecretsDiscarded()).isEqualTo(2);
    assertThat(impact.getConfigurationsChanged()).isEqualTo(3);
    assertThat(impact.getConnectedAccountsEnded().getFewerThan()).isEqualTo(5);
    assertThat(impact.getConnectedAccountsEnded().getCount()).isNull();
    assertThat(impact.getFullSyncLibraries()).isEqualTo(3);
    assertThat(impact.getConfirmation())
        .isEqualTo(
            "Die Änderung verwirft die Zugangsdaten von 2 Bibliotheken sowie etwaiger verbundener"
                + " Konten von Personen dieses Zugangs. Die Vorgabe „Imitiertes Konto“ ändert"
                + " sich: Der Abgleichsstand von 3 Bibliotheken wird verworfen, der nächste Lauf"
                + " liest die Quelle vollständig neu. Bitte bestätigen.");
    assertThat(address.getConnectionsDiscarded()).isEqualTo(3);
    assertThat(address.getConfirmation())
        .isEqualTo(
            "Die Änderung verwirft alle Zugangsdaten und Token dieses Zugangs; 3 Verbindungen"
                + " müssen neu angemeldet werden, die gespeicherten Zugangsdaten von 2 Bibliotheken sind"
                + " neu einzutragen, etwaige verbundene Konten von Personen enden. Bitte"
                + " bestätigen.");
    assertThat(none.getConnectionsDiscarded()).isZero();
    assertThat(none.getSecretsDiscarded()).isZero();
    assertThat(none.getConfigurationsChanged()).isZero();
    assertThat(none.getConnectedAccountsEnded()).isNull();
    assertThat(none.getConfirmation()).isNull();
  }

  private static ConnectionProfileImpactResponse impactWith(Discards discards) {
    return ConnectionProfileResponseMapper.toResponse(
        new ProfileImpact(3, 3, TestPersonCounts.of(1, 0).total(), List.of(), null, discards),
        false);
  }

  @Test
  void anOptionWithoutDefaultsCarriesNone() {
    ConnectionProfileOption option =
        ConnectionProfileResponseMapper.toOption(
            new ProfileOption(profile(null), true, null, false));

    assertThat(option.getConnectorDefaults()).isNull();
    assertThat(option.getCreatable()).isTrue();
    assertThat(option.getOwnAccount()).isFalse();
  }

  private static ConnectionProfile profile(String connectorSettings) {
    ConnectionProfile profile =
        new ConnectionProfile(
            SourceType.of("PROFILE_PROBE"), Instant.parse("2026-10-04T08:00:00Z"));
    ReflectionTestUtils.setField(profile, "name", "Zugang Wiki");
    ReflectionTestUtils.setField(profile, "serverUrl", "https://wiki.example.org");
    ReflectionTestUtils.setField(profile, "authMethod", ConnectionAuthMethod.PERSONAL_SECRET);
    ReflectionTestUtils.setField(profile, "connectorSettings", connectorSettings);
    return profile;
  }
}
