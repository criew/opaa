package io.opaa.indexing.source.googledrive;

import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.Sleeper;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the Google Drive connector (ADR-0040) with its fixed Google addresses. */
@Configuration
@EnableConfigurationProperties(GoogleDriveProperties.class)
public class GoogleDriveConnectorConfiguration {

  @Bean
  DriveApiFactory googleDriveApiFactory(
      GoogleDriveProperties googleDriveProperties, TargetAddressValidator targetAddressValidator) {
    return new DriveApiFactory(
        googleDriveProperties, targetAddressValidator, Sleeper.threadSleep());
  }

  @Bean
  GoogleDriveSourceConnector googleDriveSourceConnector(
      DriveApiFactory googleDriveApiFactory, SourceSyncStateRepository sourceSyncStateRepository) {
    return new GoogleDriveSourceConnector(
        GoogleDriveSourceConnector.API_BASE,
        GoogleDriveSourceConnector.TOKEN_ENDPOINT,
        googleDriveApiFactory,
        sourceSyncStateRepository);
  }

  @Bean
  GoogleDriveIndexingExecutor googleDriveIndexingExecutor(
      DriveApiFactory googleDriveApiFactory,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService libraryFolderService,
      StaleDocumentCleanupService staleDocumentCleanupService,
      SourceSyncStateRepository sourceSyncStateRepository,
      IndexingRunTemplate indexingRunTemplate,
      SupportedDocumentFormats supportedDocumentFormats) {
    return new GoogleDriveIndexingExecutor(
        googleDriveApiFactory,
        documentIngestService,
        documentRepository,
        libraryFolderService,
        staleDocumentCleanupService,
        sourceSyncStateRepository,
        Clock.systemUTC(),
        indexingRunTemplate,
        supportedDocumentFormats);
  }
}
