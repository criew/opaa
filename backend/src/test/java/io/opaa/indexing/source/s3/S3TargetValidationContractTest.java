package io.opaa.indexing.source.s3;

import static org.mockito.Mockito.mock;

import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.indexing.spi.TargetValidationContract;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;

class S3TargetValidationContractTest extends TargetValidationContract {

  @Override
  protected SourceType type() {
    return S3SourceConnector.TYPE;
  }

  @Override
  protected SourceConnectionTestResult testConnection(
      URI target, TargetAddressValidator validator) {
    S3ClientFactory clients = new S3ClientFactory(S3Properties.defaults(), validator);
    S3SourceConnector connector =
        new S3SourceConnector(
            new S3ConnectionService(clients, Clock.systemUTC()),
            clients,
            mock(SourceSyncStateRepository.class),
            mock(S3OriginalAccess.class),
            mock(S3PushReceiver.class));
    ConnectorData bucket =
        ConnectorData.of(
            Map.of(
                "region",
                "eu-central-1",
                "pathStyle",
                true,
                "scopes",
                List.of(Map.of("bucket", "dokumente"))));
    return connector.testConnection(
        new SourceSettings(null, target.toString(), "zugangsschluessel:geheimnis", bucket), null);
  }
}
