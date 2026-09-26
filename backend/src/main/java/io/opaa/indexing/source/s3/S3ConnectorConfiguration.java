package io.opaa.indexing.source.s3;

import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.format.SupportedDocumentFormats;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.s3.events.S3EventService;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.security.TargetAddressValidator;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the S3 connector (ADR-0027); its executor reaches the core through the registry. */
@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class S3ConnectorConfiguration {

  /**
   * Builds per-library S3 stores; shares the target validation every other outbound source fetch
   * uses - the SDK's own HTTP client bypasses SourceHttpClientFactory, so the validation is applied
   * on the client and on every request instead.
   */
  @Bean
  S3ClientFactory s3ClientFactory(
      S3Properties s3Properties, TargetAddressValidator targetAddressValidator) {
    return new S3ClientFactory(s3Properties, targetAddressValidator);
  }

  @Bean
  S3SourceConnector s3SourceConnector(
      S3ConnectionService s3ConnectionService,
      S3ClientFactory s3ClientFactory,
      SourceSyncStateRepository sourceSyncStateRepository,
      S3OriginalAccess s3OriginalAccess,
      S3EventService s3EventService) {
    return new S3SourceConnector(
        s3ConnectionService,
        s3ClientFactory,
        sourceSyncStateRepository,
        s3OriginalAccess,
        s3EventService);
  }

  /**
   * Reads an indexed object back for the citation jump (ADR-0027, Entscheidung 5) - a read path
   * outside every run, served through {@link S3SourceConnector#openOriginal}.
   */
  @Bean
  S3OriginalAccess s3OriginalAccess(S3ClientFactory s3ClientFactory, S3Properties s3Properties) {
    return new S3OriginalAccess(s3ClientFactory, s3Properties);
  }

  /**
   * Declared as the concrete type, not as {@code SourceIndexingExecutor}: the event adapter ({@code
   * S3EventService}) calls {@code refreshObjects}, which the interface does not carry.
   */
  @Bean
  S3IndexingExecutor s3IndexingExecutor(
      S3ClientFactory s3ClientFactory,
      S3Properties s3Properties,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService libraryFolderService,
      StaleDocumentCleanupService staleDocumentCleanupService,
      SourceSyncStateRepository sourceSyncStateRepository,
      IndexingRunTemplate indexingRunTemplate,
      SupportedDocumentFormats supportedDocumentFormats) {
    return new S3IndexingExecutor(
        s3ClientFactory,
        s3Properties,
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
