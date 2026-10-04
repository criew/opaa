package io.opaa.indexing.source.nextcloud;

import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.filesync.ScanJournal;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the Nextcloud connector; its executor reaches the core through the registry. */
@Configuration
@EnableConfigurationProperties(NextcloudProperties.class)
public class NextcloudConnectorConfiguration {

  @Bean
  NextcloudSourceConnector nextcloudSourceConnector(
      NextcloudProperties nextcloudProperties,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy sourceRequestPolicy,
      SourceSyncStateRepository sourceSyncStateRepository) {
    return new NextcloudSourceConnector(
        nextcloudProperties,
        targetAddressValidator,
        sourceRequestPolicy,
        sourceSyncStateRepository);
  }

  @Bean
  NextcloudIndexingExecutor nextcloudIndexingExecutor(
      NextcloudProperties nextcloudProperties,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy sourceRequestPolicy,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService libraryFolderService,
      StaleDocumentCleanupService staleDocumentCleanupService,
      ScanJournal scanJournal,
      IndexingRunTemplate indexingRunTemplate,
      SupportedDocumentFormats supportedDocumentFormats) {
    return new NextcloudIndexingExecutor(
        nextcloudProperties,
        targetAddressValidator,
        sourceRequestPolicy,
        documentIngestService,
        documentRepository,
        libraryFolderService,
        staleDocumentCleanupService,
        scanJournal,
        Clock.systemUTC(),
        indexingRunTemplate,
        supportedDocumentFormats);
  }
}
