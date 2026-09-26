package io.opaa.indexing.source.confluence;

import io.opaa.indexing.attachment.AttachmentIndexer;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookService;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Confluence connector (ADR-0023); its executor reaches the core through the
 * registry.
 */
@Configuration
@EnableConfigurationProperties(ConfluenceProperties.class)
public class ConfluenceConnectorConfiguration {

  /**
   * Builds per-library Confluence clients; shares the target validation and the request policy
   * every other outbound source fetch uses.
   */
  @Bean
  ConfluenceClientFactory confluenceClientFactory(
      ConfluenceProperties confluenceProperties,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy sourceRequestPolicy) {
    return new ConfluenceClientFactory(
        confluenceProperties, targetAddressValidator, sourceRequestPolicy);
  }

  @Bean
  ConfluenceSourceConnector confluenceSourceConnector(
      ConfluenceConnectionService confluenceConnectionService,
      ConfluenceProperties confluenceProperties,
      SourceSyncStateRepository sourceSyncStateRepository,
      ConfluenceWebhookService confluenceWebhookService) {
    return new ConfluenceSourceConnector(
        confluenceConnectionService,
        confluenceProperties,
        sourceSyncStateRepository,
        confluenceWebhookService);
  }

  /**
   * Declared as the concrete type, not as {@code SourceIndexingExecutor}: {@code
   * ConfluenceWebhookService} injects the executor directly for its targeted webhook run, and
   * Spring resolves an injection point by the bean method's declared type - the registry still
   * collects it through the interface it implements.
   */
  @Bean
  ConfluenceIndexingExecutor confluenceIndexingExecutor(
      ConfluenceClientFactory confluenceClientFactory,
      ConfluenceProperties confluenceProperties,
      DocumentIngestService documentIngestService,
      AttachmentIndexer attachmentIndexer,
      DocumentRepository documentRepository,
      SourceSyncStateRepository sourceSyncStateRepository,
      StaleDocumentCleanupService staleDocumentCleanupService,
      IndexingRunTemplate indexingRunTemplate) {
    return new ConfluenceIndexingExecutor(
        confluenceClientFactory,
        confluenceProperties,
        documentIngestService,
        attachmentIndexer,
        documentRepository,
        sourceSyncStateRepository,
        staleDocumentCleanupService,
        Clock.systemUTC(),
        indexingRunTemplate);
  }
}
