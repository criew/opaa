package io.opaa;

import io.opaa.common.ClockConfiguration;
import io.opaa.common.PublicBaseUrlProperties;
import io.opaa.config.DatabaseMigrationMode;
import io.opaa.knowledge.UploadProperties;
import io.opaa.security.CredentialsEncryptionProperties;
import io.opaa.security.SettingsEncryptionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Every module registers its own {@code @ConfigurationProperties} classes in one of its
 * {@code @Configuration} classes. Registered here are only the foundation classes, which no module
 * configuration owns, and {@link UploadProperties}, which {@link
 * io.opaa.api.GlobalExceptionHandler} consumes. For the same advice the {@link ClockConfiguration}
 * is imported here: bound on the application class, both are present in every context that holds
 * the advice, a web slice included.
 */
@SpringBootApplication
@Import(ClockConfiguration.class)
@EnableConfigurationProperties({
  PublicBaseUrlProperties.class,
  CredentialsEncryptionProperties.class,
  SettingsEncryptionProperties.class,
  UploadProperties.class
})
// Enables io.opaa.audit.AuditRetentionScheduler's @Scheduled monthly retention deletion.
@EnableScheduling
public class OpaaApplication {

  /** Serves, or with {@code OPAA_MODE=migrate} only migrates the database and ends. */
  public static void main(String[] args) {
    if (DatabaseMigrationMode.requested(args)) {
      System.exit(DatabaseMigrationMode.run(args));
    }
    SpringApplication.run(OpaaApplication.class, args);
  }
}
