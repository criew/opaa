package io.opaa.indexing.source.sharepoint;

import static org.mockito.Mockito.mock;

import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.indexing.spi.TargetValidationContract;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import java.net.URI;
import java.util.List;
import java.util.Map;

/** SharePoint's address is fixed: the target stands in for the Graph API base. */
class SharePointTargetValidationContractTest extends TargetValidationContract {

  @Override
  protected SourceType type() {
    return SharePointSourceConnector.TYPE;
  }

  @Override
  protected SourceConnectionTestResult testConnection(
      URI target, TargetAddressValidator validator) {
    SharePointSourceConnector connector =
        new SharePointSourceConnector(
            target,
            "https://login.example.org/{tenant}/oauth2/v2.0/token",
            new GraphConnections(SharePointProperties.defaults(), validator, wait -> {}),
            mock(SourceSyncStateRepository.class));
    return connector.testConnection(
        new SourceSettings(
            null,
            target.toString(),
            "graph-token",
            ConnectorData.of(Map.of("libraries", List.of(Map.of("driveId", "b!drive-0"))))),
        null);
  }
}
