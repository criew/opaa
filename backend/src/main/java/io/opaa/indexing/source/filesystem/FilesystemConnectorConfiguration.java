package io.opaa.indexing.source.filesystem;

import io.opaa.indexing.FilesystemPathAllowlist;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.document.DocumentService;
import io.opaa.indexing.format.SupportedDocumentFormats;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.knowledge.LibraryFolderService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the FILESYSTEM connector; its executor reaches the core through the registry. */
@Configuration
public class FilesystemConnectorConfiguration {

  /**
   * Declared as {@link SourceIndexingExecutor}, not the concrete type: the executor carries
   * {@code @Async} and is therefore wrapped in a JDK dynamic proxy, which only implements the
   * interfaces the target class declares.
   */
  @Bean
  SourceIndexingExecutor asyncIndexingExecutor(
      DocumentService documentService,
      DocumentIngestService documentIngestService,
      FilesystemPathAllowlist filesystemPathAllowlist,
      LibraryFolderService libraryFolderService,
      IndexingRunTemplate indexingRunTemplate,
      SupportedDocumentFormats supportedDocumentFormats) {
    return new AsyncIndexingExecutor(
        documentService,
        documentIngestService,
        filesystemPathAllowlist,
        libraryFolderService,
        indexingRunTemplate,
        supportedDocumentFormats);
  }
}
