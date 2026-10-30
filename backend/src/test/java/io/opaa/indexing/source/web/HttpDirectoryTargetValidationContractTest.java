package io.opaa.indexing.source.web;

import io.opaa.indexing.spi.RemoteContentProperties;
import io.opaa.indexing.spi.RemoteOriginalAccess;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.TargetValidationContract;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.SourceRequestPolicy;
import io.opaa.test.ProductionDocumentFormats;
import java.net.URI;

class HttpDirectoryTargetValidationContractTest extends TargetValidationContract {

  @Override
  protected SourceType type() {
    return HttpDirectorySourceConnector.TYPE;
  }

  @Override
  protected SourceConnectionTestResult testConnection(
      URI target, TargetAddressValidator validator) {
    HttpDirectorySourceConnector connector =
        new HttpDirectorySourceConnector(
            new AutoindexCrawlerService(validator),
            validator,
            SourceRequestPolicy.defaults(),
            ProductionDocumentFormats.supportedFormats(),
            1024 * 1024,
            new RemoteOriginalAccess(
                new BoundedDownloader(validator), validator, new RemoteContentProperties(0, 0, 0)));
    return connector.testConnection(new SourceSettings(null, target + "/akten/", null, null), null);
  }
}
