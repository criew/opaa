package io.opaa.indexing.source.smb;

import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.ScanJournal;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the SMB connector; its executor reaches the core through the registry. */
@Configuration
@EnableConfigurationProperties(SmbProperties.class)
public class SmbConnectorConfiguration {

  @Bean
  SmbSourceConnector smbSourceConnector(
      SmbProperties smbProperties,
      TargetAddressValidator targetAddressValidator,
      SourceSyncStateRepository sourceSyncStateRepository) {
    return new SmbSourceConnector(smbProperties, targetAddressValidator, sourceSyncStateRepository);
  }

  @Bean
  SmbIndexingExecutor smbIndexingExecutor(
      SmbProperties smbProperties,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy sourceRequestPolicy,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService libraryFolderService,
      StaleDocumentCleanupService staleDocumentCleanupService,
      ScanJournal scanJournal,
      IndexingRunTemplate indexingRunTemplate,
      SupportedDocumentFormats supportedDocumentFormats) {
    return new SmbIndexingExecutor(
        smbProperties,
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
