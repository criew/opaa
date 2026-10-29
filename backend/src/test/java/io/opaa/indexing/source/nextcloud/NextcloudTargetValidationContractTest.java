package io.opaa.indexing.source.nextcloud;

import static org.mockito.Mockito.mock;

import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.indexing.spi.TargetValidationContract;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.net.URI;
import java.util.List;

class NextcloudTargetValidationContractTest extends TargetValidationContract {

  @Override
  protected SourceType type() {
    return NextcloudSourceConnector.TYPE;
  }

  @Override
  protected SourceConnectionTestResult testConnection(
      URI target, TargetAddressValidator validator) {
    NextcloudSourceConnector connector =
        new NextcloudSourceConnector(
            NextcloudProperties.defaults(),
            validator,
            SourceRequestPolicy.defaults(),
            mock(SourceSyncStateRepository.class));
    return connector.testConnection(
        NextcloudTestStores.settings(
            target + "/remote.php/dav/files/techniker/", "techniker:geheim", List.of("/Projekte")),
        null);
  }
}
