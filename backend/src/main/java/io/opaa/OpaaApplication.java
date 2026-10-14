package io.opaa;

import io.opaa.common.PublicBaseUrlProperties;
import io.opaa.knowledge.UploadProperties;
import io.opaa.security.CredentialsEncryptionProperties;
import io.opaa.security.SettingsEncryptionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Every module registers its own {@code @ConfigurationProperties} classes in one of its
 * {@code @Configuration} classes. Registered here are only the foundation classes without such a
 * home and {@link UploadProperties}, which {@link io.opaa.api.GlobalExceptionHandler} consumes:
 * bound on the application class, it is present in every context that holds the advice, a web slice
 * included.
 */
@SpringBootApplication
@EnableConfigurationProperties({
  PublicBaseUrlProperties.class,
  CredentialsEncryptionProperties.class,
  SettingsEncryptionProperties.class,
  UploadProperties.class
})
// Enables io.opaa.audit.AuditRetentionScheduler's @Scheduled monthly retention deletion (#395).
@EnableScheduling
public class OpaaApplication {

  public static void main(String[] args) {
    SpringApplication.run(OpaaApplication.class, args);
  }
}
