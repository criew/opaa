package io.opaa.connection.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.ConnectionProfileOption;
import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.connection.ConnectorReleaseService.ProfileOption;
import io.opaa.connection.profile.ConnectionProfile;
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
