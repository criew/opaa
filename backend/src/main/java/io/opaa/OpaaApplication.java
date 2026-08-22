package io.opaa;

import io.opaa.auth.AuthProperties;
import io.opaa.indexing.IndexingProperties;
import io.opaa.library.RemoteContentProperties;
import io.opaa.library.UploadProperties;
import io.opaa.security.CredentialsEncryptionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({
  IndexingProperties.class,
  AuthProperties.class,
  UploadProperties.class,
  CredentialsEncryptionProperties.class,
  RemoteContentProperties.class
})
// Enables io.opaa.audit.AuditRetentionScheduler's @Scheduled monthly retention deletion (#395).
@EnableScheduling
public class OpaaApplication {

  public static void main(String[] args) {
    SpringApplication.run(OpaaApplication.class, args);
  }
}
