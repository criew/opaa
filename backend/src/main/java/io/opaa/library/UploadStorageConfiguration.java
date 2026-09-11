package io.opaa.library;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the {@link UploadedOriginalStore} adapter from {@code opaa.upload.store} (ADR-0030,
 * Entscheidung 1). Only {@code filesystem} exists so far; any other value is a configuration
 * mistake and refuses the start rather than silently falling back to a storage the operator did not
 * ask for - the same stance {@code AuthProfileGuard} takes (ADR-0005).
 */
@Configuration
public class UploadStorageConfiguration {

  @Bean
  UploadedOriginalStore uploadedOriginalStore(UploadProperties uploadProperties) {
    String store = uploadProperties.store();
    if (!FilesystemUploadedOriginalStore.STORE_NAME.equalsIgnoreCase(store)) {
      throw new IllegalStateException(
          ("Unknown storage backend \"%s\" configured for opaa.upload.store. "
                  + "Only \"%s\" is available. See docs/handbuch/deployment.md.")
              .formatted(store, FilesystemUploadedOriginalStore.STORE_NAME));
    }
    return new FilesystemUploadedOriginalStore(uploadProperties);
  }
}
