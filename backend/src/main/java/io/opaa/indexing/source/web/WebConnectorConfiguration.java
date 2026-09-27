package io.opaa.indexing.source.web;

import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.RemoteOriginalAccess;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.SourceRequestPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the HTTP directory connector; its executor reaches the core through the registry. */
@Configuration
@EnableConfigurationProperties(CrawlProperties.class)
public class WebConnectorConfiguration {

  /**
   * The directory page the connection test reads is bounded by the same key and default as an RSS
   * detail page, {@code opaa.indexing.rss.max-page-size-bytes}.
   */
  static final long DEFAULT_MAX_PAGE_SIZE_BYTES = 5_242_880L;

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
      @Value("${opaa.indexing.rss.max-page-size-bytes:0}") long maxPageSizeBytes,
      RemoteOriginalAccess remoteOriginalAccess) {
    return new HttpDirectorySourceConnector(
        autoindexCrawlerService,
        targetAddressValidator,
        sourceRequestPolicy,
        supportedDocumentFormats,
        maxPageSizeBytes > 0 ? maxPageSizeBytes : DEFAULT_MAX_PAGE_SIZE_BYTES,
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
