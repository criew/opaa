package io.opaa.indexing.source.confluence;

import static org.mockito.Mockito.mock;

import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.indexing.spi.TargetValidationContract;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import java.net.URI;

class ConfluenceTargetValidationContractTest extends TargetValidationContract {

  @Override
  protected SourceType type() {
    return ConfluenceSourceConnector.TYPE;
  }

  @Override
  protected SourceConnectionTestResult testConnection(
      URI target, TargetAddressValidator validator) {
    ConfluenceSourceConnector connector =
        new ConfluenceSourceConnector(
            new ConfluenceConnectionService(
                new ConfluenceClientFactory(ConfluenceProperties.defaults(), validator)),
            mock(SourceSyncStateRepository.class),
            mock(ConfluencePushReceiver.class));
    return connector.testConnection(
        new SourceSettings(null, target + "/wiki", "persoenliches-token", null), null);
  }
}
