package io.opaa;

import io.opaa.auth.AuthProperties;
import io.opaa.common.PublicBaseUrlProperties;
import io.opaa.indexing.FilesystemProperties;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.SourceHttpProperties;
import io.opaa.indexing.attachment.AttachmentProperties;
import io.opaa.indexing.format.file.mail.MailProperties;
import io.opaa.indexing.format.file.office.OdfProperties;
import io.opaa.indexing.format.file.tabular.TabularProperties;
import io.opaa.indexing.source.SourceEventProperties;
import io.opaa.library.AttachmentExtractionProperties;
import io.opaa.library.ExternalAccessProperties;
import io.opaa.library.LibraryProperties;
import io.opaa.library.RemoteContentProperties;
import io.opaa.library.UploadProperties;
import io.opaa.library.UploadS3Properties;
import io.opaa.llm.RerankProperties;
import io.opaa.mail.SmtpProperties;
import io.opaa.permission.GroupSizeProperties;
import io.opaa.security.CredentialsEncryptionProperties;
import io.opaa.security.SettingsEncryptionProperties;
import io.opaa.succession.SuccessionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({
  IndexingProperties.class,
  SourceHttpProperties.class,
  AuthProperties.class,
  UploadProperties.class,
  UploadS3Properties.class,
  LibraryProperties.class,
  ExternalAccessProperties.class,
  CredentialsEncryptionProperties.class,
  RemoteContentProperties.class,
  AttachmentExtractionProperties.class,
  SettingsEncryptionProperties.class,
  SourceEventProperties.class,
  FilesystemProperties.class,
  AttachmentProperties.class,
  TabularProperties.class,
  MailProperties.class,
  OdfProperties.class,
  RerankProperties.class,
  SmtpProperties.class,
  PublicBaseUrlProperties.class,
  GroupSizeProperties.class,
  SuccessionProperties.class
})
// Enables io.opaa.audit.AuditRetentionScheduler's @Scheduled monthly retention deletion (#395).
@EnableScheduling
public class OpaaApplication {

  public static void main(String[] args) {
    SpringApplication.run(OpaaApplication.class, args);
  }
}
