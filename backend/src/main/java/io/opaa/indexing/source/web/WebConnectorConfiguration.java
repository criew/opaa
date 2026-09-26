package io.opaa.indexing.source.web;

import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.format.SupportedDocumentFormats;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.RemoteOriginalAccess;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.SourceRequestPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the HTTP directory connector; its executor reaches the core through the registry. */
@Configuration
@EnableConfigurationProperties(CrawlProperties.class)
public class WebConnectorConfiguration {

  @Bean
  AutoindexCrawlerService autoindexCrawlerService(
      TargetAddressValidator targetAddressValidator,
      CrawlProperties crawlProperties,
      SourceRequestPolicy sourceRequestPolicy) {
    return new AutoindexCrawlerService(
        targetAddressValidator, crawlProperties, sourceRequestPolicy);
  }

  @Bean
  HttpDirectorySourceConnector httpDirectorySourceConnector(
      AutoindexCrawlerService autoindexCrawlerService,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy sourceRequestPolicy,
      SupportedDocumentFormats supportedDocumentFormats,
      IndexingProperties indexingProperties,
      RemoteOriginalAccess remoteOriginalAccess) {
    return new HttpDirectorySourceConnector(
        autoindexCrawlerService,
        targetAddressValidator,
        sourceRequestPolicy,
        supportedDocumentFormats,
        indexingProperties,
        remoteOriginalAccess);
  }

  /**
   * Declared as {@link SourceIndexingExecutor}, not the concrete type: the executor carries
   * {@code @Async} and is therefore wrapped in a JDK dynamic proxy, which only implements the
   * interfaces the target class declares.
   */
  @Bean
  SourceIndexingExecutor urlIndexingExecutor(
      AutoindexCrawlerService autoindexCrawlerService,
      BoundedDownloader boundedDownloader,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      SourceRequestPolicy sourceRequestPolicy,
      CrawlProperties crawlProperties,
      LibraryFolderService libraryFolderService,
      IndexingRunTemplate indexingRunTemplate,
      SupportedDocumentFormats supportedDocumentFormats) {
    return new UrlIndexingExecutor(
        autoindexCrawlerService,
        boundedDownloader,
        documentIngestService,
        documentRepository,
        crawlProperties,
        libraryFolderService,
        sourceRequestPolicy,
        indexingRunTemplate,
        supportedDocumentFormats);
  }
}
