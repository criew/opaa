package io.opaa.indexing.source.smb;

import static org.mockito.Mockito.mock;

import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.indexing.spi.TargetValidationContract;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import java.net.URI;

class SmbTargetValidationContractTest extends TargetValidationContract {

  @Override
  protected SourceType type() {
    return SmbSourceConnector.TYPE;
  }

  @Override
  protected SourceConnectionTestResult testConnection(
      URI target, TargetAddressValidator validator) {
    SmbSourceConnector connector =
        new SmbSourceConnector(
            SmbProperties.defaults(), validator, mock(SourceSyncStateRepository.class));
    return connector.testConnection(
        new SourceSettings(
            null, "smb://" + target.getRawAuthority() + "/Daten", "RATHAUS\\svc:geheim", null),
        null);
  }

  @Override
  protected boolean speaksHttp() {
    return false;
  }
}
