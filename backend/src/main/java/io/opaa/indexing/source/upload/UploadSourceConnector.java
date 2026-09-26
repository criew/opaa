package io.opaa.indexing.source.upload;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceSettings;
import java.util.Set;

/**
 * The source of an upload library (ADR-0017): no run, no configuration, no connection to test - its
 * documents arrive one by one through the upload endpoint.
 */
public class UploadSourceConnector implements SourceConnector {

  private static final SourceConnectorDescriptor DESCRIPTOR =
      new SourceConnectorDescriptor(DocumentSourceType.UPLOAD, false, Set.of(), null, null);

  @Override
  public SourceConnectorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    if (requested.sourcePath() != null
        || requested.sourceUrl() != null
        || requested.sourceProxy() != null
        || requested.sourceCredentials() != null
        || requested.sourceInsecureSsl()) {
      throw new ValidationException("sourceType UPLOAD erlaubt keine Quellkonfiguration");
    }
    return requested;
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings) {
    throw new ValidationException("sourceType UPLOAD unterstützt keinen Verbindungstest");
  }
}
