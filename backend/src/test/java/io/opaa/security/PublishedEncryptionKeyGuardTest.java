package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.TestEncryptionKeys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The {@code oidc} profile refuses AES keys published in the repository - the {@code dev} defaults
 * and the keys of the chart's CI value sets - and names the variable; an own key or none at all
 * starts, and the {@code dev} profile is not judged.
 */
class PublishedEncryptionKeyGuardTest {

  private static final String DEV_CREDENTIALS_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
  private static final String CI_SETTINGS_KEY = "TklDSFQtRlVFUi1ERU4tUFJPRFVLVElWQkVUUklFQjI=";
  private static final String OWN_KEY = TestEncryptionKeys.generate();

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(PropertiesBinding.class, PublishedEncryptionKeyGuard.class);

  @EnableConfigurationProperties({
    CredentialsEncryptionProperties.class,
    SettingsEncryptionProperties.class
  })
  static class PropertiesBinding {}

  @Test
  void oidcProfileRefusesTheDevCredentialsKey() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=oidc",
            "opaa.security.credentials.encryption-key=" + DEV_CREDENTIALS_KEY)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("OPAA_CREDENTIALS_ENCRYPTION_KEY")
                    .hasMessageNotContaining("OPAA_SETTINGS_ENCRYPTION_KEY")
                    .hasMessageContaining("openssl rand -base64 32"));
  }

  @Test
  void oidcProfileRefusesAKeyOfTheCiValueSets() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=oidc",
            "opaa.security.credentials.encryption-key=" + OWN_KEY,
            "opaa.security.settings.encryption-key=" + CI_SETTINGS_KEY)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("OPAA_SETTINGS_ENCRYPTION_KEY")
                    .hasMessageNotContaining("OPAA_CREDENTIALS_ENCRYPTION_KEY"));
  }

  @Test
  void oidcProfileStartsWithOwnKeys() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=oidc",
            "opaa.security.credentials.encryption-key=" + OWN_KEY,
            "opaa.security.settings.encryption-key=" + OWN_KEY)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void oidcProfileStartsWithoutKeys() {
    contextRunner
        .withPropertyValues("spring.profiles.active=oidc")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void devProfileKeepsItsPublishedKeys() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=dev",
            "opaa.security.credentials.encryption-key=" + DEV_CREDENTIALS_KEY,
            "opaa.security.settings.encryption-key=" + CI_SETTINGS_KEY)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(PublishedEncryptionKeyGuard.class));
  }
}
