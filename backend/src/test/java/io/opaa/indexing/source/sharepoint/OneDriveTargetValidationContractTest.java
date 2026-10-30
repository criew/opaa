package io.opaa.indexing.source.sharepoint;

import static org.mockito.Mockito.mock;

import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.indexing.spi.TargetValidationContract;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import java.net.URI;

/** OneDrive's address is fixed: the target stands in for the Graph API base. */
class OneDriveTargetValidationContractTest extends TargetValidationContract {

  @Override
  protected SourceType type() {
    return OneDriveSourceConnector.TYPE;
  }

  @Override
  protected SourceConnectionTestResult testConnection(
      URI target, TargetAddressValidator validator) {
    OneDriveSourceConnector connector =
        new OneDriveSourceConnector(
            target,
            "https://login.example.org/{tenant}/oauth2/v2.0/authorize",
            "https://login.example.org/{tenant}/oauth2/v2.0/token",
            new GraphConnections(SharePointProperties.defaults(), validator, wait -> {}),
            mock(SourceSyncStateRepository.class));
    return connector.testConnection(
        new SourceSettings(null, target.toString(), "graph-token", OneDriveTestDrives.drive("b!x")),
        null);
  }
}
