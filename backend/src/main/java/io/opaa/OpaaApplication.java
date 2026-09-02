package io.opaa;

import io.opaa.auth.AuthProperties;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.pipeline.mail.MailProperties;
import io.opaa.indexing.pipeline.office.OdfProperties;
import io.opaa.indexing.pipeline.tabular.TabularProperties;
import io.opaa.indexing.source.web.CrawlProperties;
import io.opaa.library.RemoteContentProperties;
import io.opaa.library.UploadProperties;
import io.opaa.llm.RerankProperties;
import io.opaa.security.CredentialsEncryptionProperties;
import io.opaa.security.SettingsEncryptionProperties;
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
  RemoteContentProperties.class,
  SettingsEncryptionProperties.class,
  CrawlProperties.class,
  TabularProperties.class,
  MailProperties.class,
  OdfProperties.class,
  RerankProperties.class
})
// Enables io.opaa.audit.AuditRetentionScheduler's @Scheduled monthly retention deletion (#395).
@EnableScheduling
public class OpaaApplication {

  public static void main(String[] args) {
    SpringApplication.run(OpaaApplication.class, args);
  }
}
