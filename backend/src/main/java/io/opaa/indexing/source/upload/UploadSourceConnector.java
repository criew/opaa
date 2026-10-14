package io.opaa.indexing.source.upload;

import io.opaa.common.ValidationException;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.OriginalAccess;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceConnector;
import io.opaa.indexing.spi.SourceConnectorDescriptor;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
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
      SourceConnectorDescriptor.acceptingUploads(SourceType.UPLOAD, "Upload");

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
        || requested.sourceCredentials() != null) {
      throw new ValidationException("sourceType UPLOAD erlaubt keine Quellkonfiguration");
    }
    return requested;
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    throw new ValidationException("sourceType UPLOAD unterstützt keinen Verbindungstest");
  }

  @Override
  public Optional<DocumentContent> openOriginal(
      Document document, KnowledgeLibrary library, SourceSettings settings) {
    return UploadedOriginalRef.of(document)
        .flatMap(
            ref ->
                uploadedOriginalStore.openForDownload(
                    ref, document.getFileName(), document.getContentType()));
  }
}
