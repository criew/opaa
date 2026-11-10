package io.opaa.query.sandbox;

import io.opaa.compliance.DataImpact;
import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The OpenSandbox server behind the tools {@code run_code} and {@code fill_template} (ADR-0073),
 * {@code opaa.sandbox.*}. Off by default; switched on, the start fails without an address or API
 * key.
 *
 * @param enabled offers {@code run_code} and {@code fill_template} to the agent loop; each agent
 *     and the tool's own switch decide further.
 * @param baseUrl address of the OpenSandbox server the sandboxes are created on.
 * @param apiKey API key of the OpenSandbox server.
 * @param image container image of a sandbox: Python with the document libraries.
 * @param runTimeout time limit of one script; a run's own remaining time may shorten it.
 * @param maxLifetime the longest a sandbox may live; must not exceed the server's {@code
 *     max_sandbox_timeout_seconds}.
 * @param cpu CPU limit of one sandbox, e.g. {@code 1} or {@code 500m}.
 * @param memory memory limit of one sandbox, e.g. {@code 1Gi}.
 */
@ConfigurationProperties(prefix = "opaa.sandbox")
@InstallationWide
@NoDataImpact("connection and limits of the sandbox server")
public record SandboxProperties(
    @DefaultValue("false") @DataImpact boolean enabled,
    @DataImpact String baseUrl,
    String apiKey,
    @DefaultValue("opaa/sandbox-python:1") String image,
    @DefaultValue("60s") @NoDataImpact("a time limit of one script") Duration runTimeout,
    @DefaultValue("15m") @NoDataImpact("the longest life of one sandbox") Duration maxLifetime,
    @DefaultValue("1") String cpu,
    @DefaultValue("1Gi") String memory) {

  public SandboxProperties {
    if (enabled) {
      if (baseUrl == null || baseUrl.isBlank()) {
        throw new IllegalArgumentException("opaa.sandbox.base-url must be set");
      }
      if (apiKey == null || apiKey.isBlank()) {
        throw new IllegalArgumentException("opaa.sandbox.api-key must be set");
      }
    }
    if (runTimeout == null || runTimeout.isNegative() || runTimeout.isZero()) {
      throw new IllegalArgumentException("opaa.sandbox.run-timeout must be positive");
    }
    if (maxLifetime == null || maxLifetime.compareTo(Duration.ofMinutes(1)) < 0) {
      throw new IllegalArgumentException("opaa.sandbox.max-lifetime must be at least 60s");
    }
  }
}
