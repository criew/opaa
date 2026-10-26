package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.knowledge.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The audit state of a profile names client ID, tenant and scopes with their value and the
 * connector defaults only by key, since a default may name a person (the delegated account of
 * Google Drive); the {@code *Set} flags stay beside them.
 */
class ConnectionProfileAuditStateTest {

  private static final Instant NOW = Instant.parse("2026-10-27T08:00:00Z");

  @Test
  void clientIdTenantScopesAndConnectorDefaultsAppearWithTheirValue() {
    ConnectionProfile profile = new ConnectionProfile(SourceType.of("SHAREPOINT"), NOW);
    profile.replace(
        new ConnectionProfileValues(
            "Zugang Rathaus",
            "https://rathaus.sharepoint.com",
            ConnectionAuthMethod.OAUTH,
            ConnectionOwnership.LIBRARY,
            "client-4711",
            null,
            "rathaus-tenant",
            "Sites.Read.All offline_access",
            ConnectorData.of(Map.of("subject", "anna.muster@stadt.example", "includeLists", true))),
        null,
        NOW);

    Map<String, Object> state = ConnectionProfileService.auditState(profile);

    assertThat(state)
        .containsEntry("clientId", "client-4711")
        .containsEntry("tenant", "rathaus-tenant")
        .containsEntry("scopes", "Sites.Read.All offline_access")
        .containsEntry("connectorSettingsKeys", List.of("includeLists", "subject"))
        .doesNotContainKey("connectorSettings")
        .containsEntry("clientIdSet", true)
        .containsEntry("tenantSet", true)
        .containsEntry("scopesSet", true)
        .containsEntry("connectorSettingsSet", true);
    assertThat(state.toString()).doesNotContain("anna.muster");
  }

  @Test
  void anUnsetValueAppearsEmptyBesideItsFlag() {
    ConnectionProfile profile = new ConnectionProfile(SourceType.of("CONFLUENCE"), NOW);
    profile.replace(
        new ConnectionProfileValues(
            "Zugang Wiki",
            "https://wiki.stadt.example",
            ConnectionAuthMethod.PERSONAL_SECRET,
            ConnectionOwnership.BOTH,
            null,
            null,
            null,
            null,
            null),
        null,
        NOW);

    Map<String, Object> state = ConnectionProfileService.auditState(profile);

    assertThat(state)
        .containsEntry("clientId", "")
        .containsEntry("tenant", "")
        .containsEntry("scopes", "")
        .containsEntry("connectorSettingsKeys", List.of())
        .containsEntry("clientIdSet", false)
        .containsEntry("tenantSet", false)
        .containsEntry("scopesSet", false)
        .containsEntry("connectorSettingsSet", false);
  }

  @Test
  void aChangeOfTheConnectorDefaultsNamesTheChangedKeysWithoutTheirValues() {
    String before = "{\"subject\":\"anna.muster@stadt.example\",\"region\":\"eu\",\"keep\":true}";
    String after = "{\"subject\":\"ben.beispiel@stadt.example\",\"keep\":true,\"lists\":false}";

    assertThat(ConnectionProfileService.changedConnectorSettingsKeys(before, after))
        .containsExactly("lists", "region", "subject");
    assertThat(ConnectionProfileService.changedConnectorSettingsKeys(null, null)).isEmpty();
    assertThat(ConnectionProfileService.changedConnectorSettingsKeys(null, after))
        .containsExactly("keep", "lists", "subject");
  }
}
