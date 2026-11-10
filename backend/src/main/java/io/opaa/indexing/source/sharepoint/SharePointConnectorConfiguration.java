package io.opaa.indexing.source.sharepoint;

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

/**
 * Registers the SharePoint and OneDrive connectors with the fixed addresses of Microsoft Graph and
 * Entra; both use the bounds of {@link SharePointProperties}, each its own {@link GraphConnections}
 * so their calls count under their own connector type.
 */
@Configuration
@EnableConfigurationProperties(SharePointProperties.class)
public class SharePointConnectorConfiguration {

  @Bean
  GraphConnections sharePointGraphConnections(
      SharePointProperties sharePointProperties, TargetAddressValidator targetAddressValidator) {
    return new GraphConnections(
        sharePointProperties, targetAddressValidator, Sleeper.threadSleep(), "sharepoint");
  }

  @Bean
  GraphConnections oneDriveGraphConnections(
      SharePointProperties sharePointProperties, TargetAddressValidator targetAddressValidator) {
    return new GraphConnections(
        sharePointProperties, targetAddressValidator, Sleeper.threadSleep(), "onedrive");
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
      ScanJournal scanJournal,
      FullSyncRhythm fullSyncRhythm,
      IndexingRunTemplate indexingRunTemplate,
      SupportedDocumentFormats supportedDocumentFormats,
      Clock clock) {
    return new SharePointIndexingExecutor(
        sharePointGraphConnections,
        documentIngestService,
        documentRepository,
        libraryFolderService,
        scanJournal,
        fullSyncRhythm,
        clock,
        indexingRunTemplate,
        supportedDocumentFormats);
  }

  @Bean
  OneDriveSourceConnector oneDriveSourceConnector(
      GraphConnections oneDriveGraphConnections,
      SourceSyncStateRepository sourceSyncStateRepository) {
    return new OneDriveSourceConnector(
        SharePointSourceConnector.API_BASE,
        OneDriveSourceConnector.AUTHORIZATION_TEMPLATE,
        SharePointSourceConnector.TOKEN_TEMPLATE,
        oneDriveGraphConnections,
        sourceSyncStateRepository);
  }

  @Bean
  OneDriveIndexingExecutor oneDriveIndexingExecutor(
      GraphConnections oneDriveGraphConnections,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService libraryFolderService,
      ScanJournal scanJournal,
      FullSyncRhythm fullSyncRhythm,
      IndexingRunTemplate indexingRunTemplate,
      SupportedDocumentFormats supportedDocumentFormats,
      Clock clock) {
    return new OneDriveIndexingExecutor(
        oneDriveGraphConnections,
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
