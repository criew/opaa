package io.opaa.test;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.DockerClientFactory;

/**
 * Registers {@code opaa.indexing.target-validation.allowlist} with the three hosts this suite's own
 * test doubles listen on: the Docker host address (loopback on a plain host, the bridge gateway
 * inside a container), {@code localhost} and {@code 127.0.0.1}.
 *
 * <p>Target validation itself stays <b>on</b> - the allowlist matches by host name, never by
 * resolved address, so every refusal a test asserts (a private endpoint, a proxy, a bucket host
 * such as {@code dokumente.localhost}) is still the real one.
 *
 * <p>The value is identical for every class of the signature; declared once here, it keeps the
 * context cache key shared. No class asserts that one of these three hosts is blocked; the ones
 * that exercise blocking hand-build their executor with a validator of their own.
 */
final class OpaaTestTargetAllowlistInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  @Override
  public void initialize(ConfigurableApplicationContext applicationContext) {
    applicationContext
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "opaaTestTargetAllowlist",
                Map.of(
                    "opaa.indexing.target-validation.allowlist",
                    DockerClientFactory.instance().dockerHostIpAddress()
                        + ",localhost,127.0.0.1")));
  }
}
