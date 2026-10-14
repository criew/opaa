package io.opaa.mcp;

import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Keeps the Spring AI MCP server out of the context while the installation has switched the
 * external-access feature off (ADR-0044). The starter's auto-configuration knows nothing of the
 * flag and would otherwise register its own, unauthenticated endpoint on {@code /mcp} - with the
 * channel's filter chain gone, nothing would guard it.
 *
 * <p>The override is the first property source, so no other setting of {@code
 * spring.ai.mcp.server.enabled} can bring the server back while the feature is off. With the
 * feature on, this class adds nothing and the configuration applies unchanged.
 */
public class McpServerSwitch implements EnvironmentPostProcessor {

  static final String PROPERTY_SOURCE = "opaaExternalAccessSwitchedOff";
  static final String SERVER_ENABLED = "spring.ai.mcp.server.enabled";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!FeatureFlags.isEnabled(environment, Feature.EXTERNAL_ACCESS)) {
      environment
          .getPropertySources()
          .addFirst(new MapPropertySource(PROPERTY_SOURCE, Map.of(SERVER_ENABLED, "false")));
    }
  }
}
