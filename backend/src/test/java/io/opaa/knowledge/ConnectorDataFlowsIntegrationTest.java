package io.opaa.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.ComplianceManifest.DataFlowEntry;
import io.opaa.compliance.ComplianceManifestLoader;
import io.opaa.indexing.registry.SourceConnectorRegistry;
import io.opaa.indexing.spi.SourceConnectorDescriptor;
import io.opaa.test.OpaaIntegrationTest;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Keeps the connector data flows of the disclosure complete: every connector the installation
 * registers that reaches outside has its data flow, and every such flow is one of the manifest.
 */
@OpaaIntegrationTest
class ConnectorDataFlowsIntegrationTest {

  /** Source types that read only from the server itself or from uploads and send nothing out. */
  private static final Set<String> LOCAL_CONNECTORS = Set.of("FILESYSTEM", "UPLOAD");

  @Autowired private SourceConnectorRegistry registry;

  @Test
  void everyRegisteredConnectorThatReachesOutHasItsDataFlow() {
    Set<String> types =
        registry.descriptors().stream()
            .map(SourceConnectorDescriptor::type)
            .filter(type -> isDelivered(registry.connector(type).getClass()))
            .map(SourceType::key)
            .filter(type -> !LOCAL_CONNECTORS.contains(type))
            .collect(Collectors.toSet());

    assertThat(types).isNotEmpty();
    assertThat(ConnectorDataFlows.BY_TYPE).containsOnlyKeys(types);
  }

  /** A connector of the product, not a probe the test context registers from the test classes. */
  private static boolean isDelivered(Class<?> connector) {
    return connector
        .getProtectionDomain()
        .getCodeSource()
        .getLocation()
        .equals(ConnectorDataFlows.class.getProtectionDomain().getCodeSource().getLocation());
  }

  @Test
  void everyConnectorFlowIsOneOfTheManifest() {
    Set<String> flows =
        ComplianceManifestLoader.load().dataFlows().stream()
            .map(DataFlowEntry::id)
            .collect(Collectors.toSet());

    assertThat(flows).containsAll(ConnectorDataFlows.BY_TYPE.values());
  }
}
