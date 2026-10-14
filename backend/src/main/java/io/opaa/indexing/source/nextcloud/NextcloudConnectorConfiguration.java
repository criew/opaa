package io.opaa.indexing.source.nextcloud;

import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.run.IndexingRunTemplate;
import io.opaa.indexing.spi.ScanJournal;
import io.opaa.indexing.spi.SourceSyncStateRepository;
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
      ScanJournal scanJournal,
      IndexingRunTemplate indexingRunTemplate,
      SupportedDocumentFormats supportedDocumentFormats,
      Clock clock) {
    return new NextcloudIndexingExecutor(
        nextcloudProperties,
        targetAddressValidator,
        sourceRequestPolicy,
        documentIngestService,
        documentRepository,
        libraryFolderService,
        scanJournal,
        clock,
        indexingRunTemplate,
        supportedDocumentFormats);
  }
}
