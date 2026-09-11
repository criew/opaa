package io.opaa.library;

import io.opaa.indexing.source.s3.S3AccessException;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Whether the S3 upload store answers (ADR-0030, Entscheidung 9): {@code UP} with endpoint and
 * bucket, {@code DOWN} with the German, credential-free reason. Shown by the {@code upload-store}
 * health group only - see {@link UploadStoreHealthGroup} for why not by the overall status.
 */
final class UploadStoreHealthIndicator implements HealthIndicator {

  private final S3UploadedOriginalStore store;

  UploadStoreHealthIndicator(S3UploadedOriginalStore store) {
    this.store = store;
  }

  @Override
  public Health health() {
    Health.Builder health =
        Health.unknown()
            .withDetail("endpoint", store.endpoint())
            .withDetail("bucket", store.bucket());
    try {
      store.probe();
      return health.up().build();
    } catch (S3AccessException e) {
      return health.down().withDetail("reason", e.getMessage()).build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return health.down().withDetail("reason", "Die Prüfung wurde unterbrochen.").build();
    }
  }
}
