package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.knowledge.SourceType;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The audit state of a profile names client ID, tenant, scopes and connector defaults with their
 * value, so a change shows before and after; the {@code *Set} flags stay beside them.
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
            ConnectorData.of(Map.of("includeLists", true))),
        null,
        NOW);

    Map<String, Object> state = ConnectionProfileService.auditState(profile);

    assertThat(state)
        .containsEntry("clientId", "client-4711")
        .containsEntry("tenant", "rathaus-tenant")
        .containsEntry("scopes", "Sites.Read.All offline_access")
        .containsEntry("connectorSettings", "{\"includeLists\":true}")
        .containsEntry("clientIdSet", true)
        .containsEntry("tenantSet", true)
        .containsEntry("scopesSet", true)
        .containsEntry("connectorSettingsSet", true);
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
        .containsEntry("connectorSettings", "")
        .containsEntry("clientIdSet", false)
        .containsEntry("tenantSet", false)
        .containsEntry("scopesSet", false)
        .containsEntry("connectorSettingsSet", false);
  }
}
