package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.indexing.source.ConnectorData;
import java.time.LocalDate;

/**
 * The editable fields of a {@link ConnectionProfile} as a request carries them; the client secret
 * travels beside them, never in here.
 */
public record ConnectionProfileValues(
    String name,
    String serverUrl,
    ConnectionAuthMethod authMethod,
    ConnectionOwnership ownership,
    String clientId,
    LocalDate clientSecretExpiresOn,
    String tenant,
    String scopes,
    ConnectorData connectorSettings,
    String sourceProxy,
    boolean sourceInsecureSsl) {

  public ConnectionProfileValues withConnectorSettings(ConnectorData settings) {
    return new ConnectionProfileValues(
        name,
        serverUrl,
        authMethod,
        ownership,
        clientId,
        clientSecretExpiresOn,
        tenant,
        scopes,
        settings,
        sourceProxy,
        sourceInsecureSsl);
  }
}
