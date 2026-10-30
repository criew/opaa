package io.opaa.indexing.source.rss;

import static org.mockito.Mockito.mock;

import io.opaa.indexing.spi.RemoteContentProperties;
import io.opaa.indexing.spi.RemoteOriginalAccess;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.TargetValidationContract;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.net.URI;

class RssFeedTargetValidationContractTest extends TargetValidationContract {

  @Override
  protected SourceType type() {
    return RssFeedSourceConnector.TYPE;
  }

  @Override
  protected SourceConnectionTestResult testConnection(
      URI target, TargetAddressValidator validator) {
    RssFeedSourceConnector connector =
        new RssFeedSourceConnector(
            new RssFeedParser(),
            mock(RssFeedStateRepository.class),
            validator,
            SourceRequestPolicy.defaults(),
            new RssFeedProperties(0, 0, 0, 0, 0, 0),
            new RemoteOriginalAccess(
                new BoundedDownloader(validator), validator, new RemoteContentProperties(0, 0, 0)));
    return connector.testConnection(
        new SourceSettings(null, target + "/feed.xml", null, null), null);
  }
}
