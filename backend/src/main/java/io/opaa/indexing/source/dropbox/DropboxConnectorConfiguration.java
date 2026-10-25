package io.opaa.indexing.source.dropbox;

import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.run.IndexingRunTemplate;
import io.opaa.indexing.spi.FullSyncRhythm;
import io.opaa.indexing.spi.ScanJournal;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.Sleeper;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the Dropbox connector with the addresses of {@link DropboxProperties}. */
@Configuration
@EnableConfigurationProperties(DropboxProperties.class)
public class DropboxConnectorConfiguration {

  @Bean
  DropboxApiFactory dropboxApiFactory(
      DropboxProperties dropboxProperties, TargetAddressValidator targetAddressValidator) {
    return new DropboxApiFactory(dropboxProperties, targetAddressValidator, Sleeper.threadSleep());
  }

  @Bean
  DropboxSourceConnector dropboxSourceConnector(
      DropboxApiFactory dropboxApiFactory, SourceSyncStateRepository sourceSyncStateRepository) {
    return new DropboxSourceConnector(dropboxApiFactory, sourceSyncStateRepository);
  }

  @Bean
  DropboxIndexingExecutor dropboxIndexingExecutor(
      DropboxApiFactory dropboxApiFactory,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService libraryFolderService,
      ScanJournal scanJournal,
      FullSyncRhythm fullSyncRhythm,
      IndexingRunTemplate indexingRunTemplate,
      SupportedDocumentFormats supportedDocumentFormats,
      Clock clock) {
    return new DropboxIndexingExecutor(
        dropboxApiFactory,
        documentIngestService,
        documentRepository,
        libraryFolderService,
        scanJournal,
        fullSyncRhythm,
        clock,
        indexingRunTemplate,
        supportedDocumentFormats);
  }
}
