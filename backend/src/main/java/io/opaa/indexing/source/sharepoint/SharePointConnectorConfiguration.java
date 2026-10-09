package io.opaa.indexing.source.sharepoint;

import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.ScanJournal;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.Sleeper;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the SharePoint connector with the fixed addresses of Microsoft Graph and Entra. */
@Configuration
@EnableConfigurationProperties(SharePointProperties.class)
public class SharePointConnectorConfiguration {

  @Bean
  GraphConnections sharePointGraphConnections(
      SharePointProperties sharePointProperties, TargetAddressValidator targetAddressValidator) {
    return new GraphConnections(
        sharePointProperties, targetAddressValidator, Sleeper.threadSleep());
  }

  @Bean
  SharePointSourceConnector sharePointSourceConnector(
      GraphConnections sharePointGraphConnections,
      SourceSyncStateRepository sourceSyncStateRepository) {
    return new SharePointSourceConnector(
        SharePointSourceConnector.API_BASE,
        SharePointSourceConnector.TOKEN_TEMPLATE,
        sharePointGraphConnections,
        sourceSyncStateRepository);
  }

  @Bean
  SharePointIndexingExecutor sharePointIndexingExecutor(
      GraphConnections sharePointGraphConnections,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService libraryFolderService,
      StaleDocumentCleanupService staleDocumentCleanupService,
      ScanJournal scanJournal,
      IndexingRunTemplate indexingRunTemplate,
      SupportedDocumentFormats supportedDocumentFormats) {
    return new SharePointIndexingExecutor(
        sharePointGraphConnections,
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
