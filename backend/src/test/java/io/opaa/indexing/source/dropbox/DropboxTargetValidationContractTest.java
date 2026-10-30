package io.opaa.indexing.source.dropbox;

import static org.mockito.Mockito.mock;

import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.Secret;
import io.opaa.indexing.spi.SecretKind;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.indexing.spi.TargetValidationContract;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import java.net.URI;
import java.util.List;
import java.util.Map;

/** Dropbox's address is fixed: the target stands in for its API base. */
class DropboxTargetValidationContractTest extends TargetValidationContract {

  @Override
  protected SourceType type() {
    return DropboxSourceConnector.TYPE;
  }

  @Override
  protected SourceConnectionTestResult testConnection(
      URI target, TargetAddressValidator validator) {
    DropboxSourceConnector connector =
        new DropboxSourceConnector(
            new DropboxApiFactory(DropboxProperties.at(target), validator, wait -> {}),
            mock(SourceSyncStateRepository.class));
    return connector.testConnection(
        new SourceSettings(null, null, null, ConnectorData.of(Map.of("folders", List.of("/Akten"))))
            .withCredentials(new Secret(SecretKind.ACCESS_TOKEN, FakeDropboxServer.TOKEN)),
        null);
  }
}
