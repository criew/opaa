package io.opaa.library;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the {@link UploadedOriginalStore} adapter from {@code opaa.upload.store} (ADR-0030,
 * Entscheidung 1): {@code filesystem} or {@code s3}. Any other value, and an {@code s3} without its
 * required settings, refuses the start rather than silently falling back to a storage the operator
 * did not ask for - the same stance {@code AuthProfileGuard} takes (ADR-0005). A store that is
 * configured but not reachable does not refuse the start (Entscheidung 9); it is reported by its
 * own health contributor, which the {@code upload-store} group shows and the overall health status
 * leaves out.
 */
@Configuration
public class UploadStorageConfiguration {

  @Bean
  UploadedOriginalStore uploadedOriginalStore(
      UploadProperties uploadProperties, UploadS3Properties s3Properties) {
    String store = uploadProperties.store();
    if (FilesystemUploadedOriginalStore.STORE_NAME.equalsIgnoreCase(store)) {
      return new FilesystemUploadedOriginalStore(uploadProperties);
    }
    if (S3UploadedOriginalStore.STORE_NAME.equalsIgnoreCase(store)) {
      s3Properties.requireComplete();
      return new S3UploadedOriginalStore(s3Properties);
    }
    throw new IllegalStateException(
        ("Unknown storage backend \"%s\" configured for opaa.upload.store. "
                + "Available are \"%s\" and \"%s\". See docs/handbuch/deployment.md.")
            .formatted(
                store,
                FilesystemUploadedOriginalStore.STORE_NAME,
                S3UploadedOriginalStore.STORE_NAME));
  }

  /** The contributor {@code uploadStore}, only where there is a remote store to probe. */
  @Bean
  @ConditionalOnProperty(
      name = "opaa.upload.store",
      havingValue = S3UploadedOriginalStore.STORE_NAME)
  HealthIndicator uploadStoreHealthIndicator(UploadedOriginalStore uploadedOriginalStore) {
    if (!(uploadedOriginalStore instanceof S3UploadedOriginalStore s3Store)) {
      throw new IllegalStateException(
          "opaa.upload.store=s3 selected, but the store is " + uploadedOriginalStore.getClass());
    }
    return new UploadStoreHealthIndicator(s3Store);
  }

  @Bean
  @ConditionalOnProperty(
      name = "opaa.upload.store",
      havingValue = S3UploadedOriginalStore.STORE_NAME)
  HealthEndpointGroupsPostProcessor uploadStoreHealthGroup() {
    return new UploadStoreHealthGroup();
  }
}
