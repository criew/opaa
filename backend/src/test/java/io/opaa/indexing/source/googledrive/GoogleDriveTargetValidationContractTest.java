package io.opaa.indexing.source.googledrive;

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

/** Google Drive's address is fixed: the target stands in for its API base. */
class GoogleDriveTargetValidationContractTest extends TargetValidationContract {

  @Override
  protected SourceType type() {
    return GoogleDriveSourceConnector.TYPE;
  }

  @Override
  protected SourceConnectionTestResult testConnection(
      URI target, TargetAddressValidator validator) {
    GoogleDriveSourceConnector connector =
        new GoogleDriveSourceConnector(
            target,
            URI.create("https://oauth2.example.org/token"),
            new DriveApiFactory(GoogleDriveProperties.defaults(), validator, wait -> {}),
            mock(SourceSyncStateRepository.class));
    return connector.testConnection(
        new SourceSettings(
            null,
            target.toString(),
            FakeDriveServer.TOKEN,
            ConnectorData.of(Map.of("scopes", List.of(Map.of("drive", "drive0"))))),
        null);
  }
}
