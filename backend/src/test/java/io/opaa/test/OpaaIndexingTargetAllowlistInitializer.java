package io.opaa.test;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.DockerClientFactory;

/**
 * Registers {@code opaa.indexing.target-validation.allowlist} for every class carrying {@link
 * OpaaIndexingIntegrationTest}, naming the Docker host address - loopback on a plain host, the
 * bridge gateway inside a container - so a real executor bean of the shared context may reach a
 * Testcontainers service (MinIO's webhook path, {@code S3EventPathMinioIntegrationTest}) without a
 * context-splitting {@code @DynamicPropertySource}. The value is identical for every class of the
 * signature; declared once here, it keeps the context cache key shared.
 *
 * <p>No class of this signature asserts that the Docker host itself is blocked; the ones that
 * exercise blocking hand-build their executor with a validator of their own.
 */
final class OpaaIndexingTargetAllowlistInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  @Override
  public void initialize(ConfigurableApplicationContext applicationContext) {
    applicationContext
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "opaaIndexingTargetAllowlist",
                Map.of(
                    "opaa.indexing.target-validation.allowlist",
                    DockerClientFactory.instance().dockerHostIpAddress())));
  }
}
