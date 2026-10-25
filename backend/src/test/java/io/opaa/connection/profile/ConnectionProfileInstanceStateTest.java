package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.knowledge.ConnectorDataFlows;
import io.opaa.knowledge.SourceType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * A profile that admits persons switches on the flow its connector names; a library's own profile
 * and a connector without a flow switch on nothing.
 */
class ConnectionProfileInstanceStateTest {

  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);

  @Test
  void aProfileAdmittingPersonsSwitchesOnItsConnectorsFlow() {
    List<ConnectionProfile> connectors =
        List.of(
            profile("CONFLUENCE", ConnectionOwnership.PERSON),
            profile("S3", ConnectionOwnership.LIBRARY),
            profile("FILESYSTEM", ConnectionOwnership.PERSON));
    when(profiles.findConnectorsByName()).thenReturn(connectors);
    ConnectorDataFlows connectorFlows =
        () ->
            Map.of(
                SourceType.of("CONFLUENCE"), "source-confluence",
                SourceType.of("S3"), "source-s3");

    Map<String, Boolean> flows = state(connectorFlows).dataFlows(UUID.randomUUID());

    assertThat(flows)
        .containsEntry("source-confluence", true)
        .doesNotContainKey("source-s3")
        .containsEntry("connection-oauth", false)
        .containsEntry("service-account-token", false)
        .hasSize(3);
  }

  private ConnectionProfileInstanceState state(ConnectorDataFlows connectorFlows) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("connectorDataFlows", connectorFlows);
    return new ConnectionProfileInstanceState(
        profiles, beans.getBeanProvider(ConnectorDataFlows.class));
  }

  private static ConnectionProfile profile(String type, ConnectionOwnership ownership) {
    ConnectionProfile profile = mock(ConnectionProfile.class);
    when(profile.getSourceType()).thenReturn(SourceType.of(type));
    when(profile.getOwnership()).thenReturn(ownership);
    when(profile.getAuthMethod()).thenReturn(ConnectionAuthMethod.NONE);
    return profile;
  }
}
