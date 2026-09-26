package io.opaa.indexing.source.rss;

import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.attachment.AttachmentIndexer;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.SourceRequestPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the RSS connector; its executor reaches the core through the registry. */
@Configuration
public class RssConnectorConfiguration {

  @Bean
  RssFeedParser rssFeedParser() {
    return new RssFeedParser();
  }

  /**
   * Declared as {@link SourceIndexingExecutor}, not the concrete type: the executor carries
   * {@code @Async} and is therefore wrapped in a JDK dynamic proxy, which only implements the
   * interfaces the target class declares.
   */
  @Bean
  SourceIndexingExecutor rssFeedIndexingExecutor(
      RssFeedParser rssFeedParser,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      RssFeedStateRepository rssFeedStateRepository,
      AttachmentIndexer attachmentIndexer,
      IndexingProperties properties,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy sourceRequestPolicy,
      IndexingRunTemplate indexingRunTemplate) {
    return new RssFeedIndexingExecutor(
        rssFeedParser,
        documentIngestService,
        documentRepository,
        rssFeedStateRepository,
        attachmentIndexer,
        properties,
        targetAddressValidator,
        sourceRequestPolicy,
        indexingRunTemplate);
  }
}
