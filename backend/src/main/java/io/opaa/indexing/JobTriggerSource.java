package io.opaa.indexing;

/**
 * Who started an {@link IndexingJob}: a person via "Jetzt indizieren" ({@code
 * DocumentIndexingService#triggerIndexing}), the library's own schedule ({@link
 * LibraryIndexingScheduler}), or an authenticated Confluence webhook notification ({@code
 * io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookService}, #1140). Mirrors {@code
 * io.opaa.api.dto.IndexingTriggerSource} in the OpenAPI spec - kept as a separate domain enum with
 * a different name rather than a typeMappings-generated one, so the two never collide on the same
 * simple name in a file that imports both.
 */
public enum JobTriggerSource {
  MANUAL,
  SCHEDULED,
  WEBHOOK
}
