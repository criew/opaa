package io.opaa.connection.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.ConnectionProfileImpactResponse;
import io.opaa.api.dto.ConnectionProfileOption;
import io.opaa.api.dto.ConnectionProfileResponse;
import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.connection.ConnectorReleaseService.ProfileOption;
import io.opaa.connection.account.ConnectedAccountCounts.ProfileCounts;
import io.opaa.connection.account.PersonCount;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileService.ProfileImpact;
import io.opaa.knowledge.SourceType;
import java.time.Instant;
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

    ConnectionProfileOption option =
        ConnectionProfileResponseMapper.toOption(
            new ProfileOption(profile, false, "Der Zugang ist gesperrt."));

    assertThat(option.getId()).isEqualTo(profile.getId());
    assertThat(option.getName()).isEqualTo("Zugang Wiki");
    assertThat(option.getSourceType()).isEqualTo("PROFILE_PROBE");
    assertThat(option.getServerUrl()).isEqualTo("https://wiki.example.org");
    assertThat(option.getAuthMethod()).isEqualTo(ConnectionAuthMethod.PERSONAL_SECRET);
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
        ConnectionProfileResponseMapper.toResponse(
            profile, false, 0, new ProfileCounts(PersonCount.of(0, 5), PersonCount.of(0, 5)));

    assertThat(response.getSourceProxy()).isEqualTo("proxy.example.org:8080");
    assertThat(response.getSourceInsecureSsl()).isFalse();
  }

  /** Below the minimum group size a count is only "fewer than"; at zero and above it exact. */
  @Test
  void personCountsAreExactOnlyFromTheMinimumGroupSizeOn() {
    ConnectionProfile profile = profile(null);
    ReflectionTestUtils.setField(profile, "ownership", ConnectionOwnership.PERSON);

    ConnectionProfileResponse response =
        ConnectionProfileResponseMapper.toResponse(
            profile, false, 2, new ProfileCounts(PersonCount.of(3, 5), PersonCount.of(7, 5)));
    ConnectionProfileImpactResponse impact =
        ConnectionProfileResponseMapper.toResponse(
            new ProfileImpact(2, 2, 0), PersonCount.of(0, 5), false);

    assertThat(response.getConnectionCount()).isEqualTo(2);
    assertThat(response.getConnectedAccountCount().getCount()).isNull();
    assertThat(response.getConnectedAccountCount().getFewerThan()).isEqualTo(5);
    assertThat(response.getExpiredConnectionCount().getCount()).isEqualTo(7);
    assertThat(response.getExpiredConnectionCount().getFewerThan()).isNull();
    assertThat(impact.getConnectedAccounts().getCount()).isZero();
    assertThat(impact.getConnectedAccounts().getFewerThan()).isNull();
    assertThat(impact.getConnections()).isEqualTo(2);
  }

  @Test
  void anOptionWithoutDefaultsCarriesNone() {
    ConnectionProfileOption option =
        ConnectionProfileResponseMapper.toOption(new ProfileOption(profile(null), true, null));

    assertThat(option.getConnectorDefaults()).isNull();
    assertThat(option.getCreatable()).isTrue();
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
