package io.opaa;

import io.opaa.common.ClockConfiguration;
import io.opaa.common.PublicBaseUrlProperties;
import io.opaa.config.DatabaseMigrationMode;
import io.opaa.knowledge.UploadProperties;
import io.opaa.security.CipherFormatProperties;
import io.opaa.security.CredentialsEncryptionProperties;
import io.opaa.security.PinnedAddressResolverProvider;
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
  CipherFormatProperties.class,
  UploadProperties.class
})
// Enables io.opaa.audit.AuditRetentionScheduler's @Scheduled monthly retention deletion.
@EnableScheduling
public class OpaaApplication {

  /**
   * Serves, or with {@code OPAA_MODE=migrate} only migrates the database and ends, or with {@code
   * OPAA_MODE=await-migration} only waits for that migration and ends. Refuses to start unless the
   * pinned address resolver is the resolver of this JVM.
   */
  public static void main(String[] args) {
    PinnedAddressResolverProvider.requireActive();
    DatabaseMigrationMode.Mode mode = DatabaseMigrationMode.mode(args);
    if (mode != DatabaseMigrationMode.Mode.SERVE) {
      System.exit(DatabaseMigrationMode.run(mode, args));
    }
    SpringApplication.run(OpaaApplication.class, args);
  }
}
