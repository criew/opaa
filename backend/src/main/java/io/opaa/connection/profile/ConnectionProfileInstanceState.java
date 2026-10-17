package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.compliance.InstanceDisclosure.ConnectorUsage;
import io.opaa.compliance.InstanceStateSource;
import io.opaa.knowledge.ConnectorDataFlows;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * The connection profiles by connector type and the flows they switch on, for the disclosure: OAuth
 * and client credentials go to an authorization server, a service account key to the token endpoint
 * of its provider. A profile that admits persons switches on its connector's flow, as a private
 * library may read through it; whether one does is not disclosed (ADR-0049, Entscheidung 1).
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
    Map<String, Boolean> flows = new HashMap<>();
    for (ConnectionProfile profile : connectors) {
      if (profile.getOwnership() != ConnectionOwnership.LIBRARY) {
        ConnectorDataFlows.of(profile.getSourceType()).ifPresent(flow -> flows.put(flow, true));
      }
    }
    flows.put(
        "connection-oauth",
        connectors.stream()
            .anyMatch(
                profile ->
                    profile.getAuthMethod() == ConnectionAuthMethod.OAUTH
                        || profile.getAuthMethod() == ConnectionAuthMethod.CLIENT_CREDENTIALS));
    flows.put(
        "service-account-token",
        connectors.stream()
            .anyMatch(
                profile -> profile.getAuthMethod() == ConnectionAuthMethod.SERVICE_ACCOUNT_KEY));
    return flows;
  }
}
