package io.opaa.test;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Sets {@code opaa.security.settings.encryption-key} to a key generated once per JVM, which the
 * {@code oidc} profile accepts. One key for every context of the test JVM: a value one context
 * encrypts into the shared database stays readable for the next.
 */
final class OpaaTestSettingsKeyInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  private static final String KEY = TestEncryptionKeys.generate();

  @Override
  public void initialize(ConfigurableApplicationContext applicationContext) {
    applicationContext
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "opaaTestSettingsKey", Map.of("opaa.security.settings.encryption-key", KEY)));
  }
}
