package io.opaa.indexing.source.upload;

import io.opaa.knowledge.UploadedOriginalStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the upload source; it has no run, so no executor either. */
@Configuration
public class UploadConnectorConfiguration {

  @Bean
  UploadSourceConnector uploadSourceConnector(UploadedOriginalStore uploadedOriginalStore) {
    return new UploadSourceConnector(uploadedOriginalStore);
  }
}
