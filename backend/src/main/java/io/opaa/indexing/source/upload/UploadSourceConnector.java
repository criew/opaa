package io.opaa.indexing.source.upload;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.OriginalAccess;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.UploadedOriginalRef;
import io.opaa.knowledge.UploadedOriginalStore;
import java.util.Optional;

/**
 * The source of an upload library (ADR-0017): no run, no configuration, no connection to test - its
 * documents arrive one by one through the upload endpoint, and their originals are served from the
 * application's own upload storage (ADR-0030), which resolves a reference only within the library
 * that stored it.
 */
public class UploadSourceConnector implements SourceConnector, OriginalAccess {

  private static final SourceConnectorDescriptor DESCRIPTOR =
      new SourceConnectorDescriptor(DocumentSourceType.UPLOAD, false, null, null);

  private final UploadedOriginalStore uploadedOriginalStore;

  public UploadSourceConnector(UploadedOriginalStore uploadedOriginalStore) {
    this.uploadedOriginalStore = uploadedOriginalStore;
  }

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
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    throw new ValidationException("sourceType UPLOAD unterstützt keinen Verbindungstest");
  }

  @Override
  public Optional<DocumentContent> openOriginal(Document document, KnowledgeLibrary library) {
    return UploadedOriginalRef.of(document)
        .flatMap(
            ref ->
                uploadedOriginalStore.openForDownload(
                    ref, document.getFileName(), document.getContentType()));
  }
}
