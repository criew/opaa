package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.compliance.InstanceDisclosure.ConnectorUsage;
import io.opaa.compliance.InstanceStateSource;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * The connection profiles by connector type and the sign-in flows they switch on, for the
 * disclosure: OAuth and client credentials go to an authorization server, a service account key to
 * the token endpoint of its provider.
 */
@Component
class ConnectionProfileInstanceState implements InstanceStateSource {

  private final ConnectionProfileRepository profiles;

  ConnectionProfileInstanceState(ConnectionProfileRepository profiles) {
    this.profiles = profiles;
  }

  @Override
  public List<ConnectorUsage> connectorUsage() {
    Map<String, Long> byType = new TreeMap<>();
    for (ConnectionProfile profile : profiles.findConnectorsByName()) {
      byType.merge(profile.getSourceType().key(), 1L, Long::sum);
    }
    return byType.entrySet().stream()
        .map(entry -> new ConnectorUsage(entry.getKey(), 0, entry.getValue()))
        .toList();
  }

  @Override
  public Map<String, Boolean> dataFlows() {
    List<ConnectionProfile> connectors = profiles.findConnectorsByName();
    return Map.of(
        "connection-oauth",
        connectors.stream()
            .anyMatch(
                profile ->
                    profile.getAuthMethod() == ConnectionAuthMethod.OAUTH
                        || profile.getAuthMethod() == ConnectionAuthMethod.CLIENT_CREDENTIALS),
        "service-account-token",
        connectors.stream()
            .anyMatch(
                profile -> profile.getAuthMethod() == ConnectionAuthMethod.SERVICE_ACCOUNT_KEY));
  }
}
