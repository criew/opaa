package io.opaa.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.opaa.common.Feature;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStatelessWebMvcAutoConfiguration;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

/**
 * With the external-access feature switched off (ADR-0044) the Spring AI starter registers no MCP
 * server and no route, whatever else configures it; with the feature on, its configuration applies
 * unchanged.
 */
class McpServerSwitchTest {

  private static final String SWITCHED_OFF = Feature.EXTERNAL_ACCESS.enabledProperty() + "=false";

  private final WebApplicationContextRunner starter =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  McpServerJsonMapperAutoConfiguration.class,
                  McpServerStatelessWebMvcAutoConfiguration.class,
                  McpServerStatelessAutoConfiguration.class))
          .withPropertyValues(
              "spring.ai.mcp.server.protocol=STATELESS",
              "spring.ai.mcp.server.type=SYNC",
              "spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp")
          .withInitializer(
              context ->
                  new McpServerSwitch()
                      .postProcessEnvironment(context.getEnvironment(), new SpringApplication()));

  @Test
  void theStarterServesMcpWhileTheFeatureIsOn() {
    starter.run(
        context -> {
          assertThat(context).hasSingleBean(McpStatelessSyncServer.class);
          assertThat(context).hasBean("webMvcStatelessServerRouterFunction");
        });
  }

  @Test
  void theStarterRegistersNoServerAndNoRouteWhileTheFeatureIsOff() {
    starter
        .withPropertyValues(SWITCHED_OFF, McpServerSwitch.SERVER_ENABLED + "=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(McpStatelessSyncServer.class);
              assertThat(context).doesNotHaveBean("webMvcStatelessServerRouterFunction");
            });
  }

  @Test
  void theFeatureOnLeavesTheEnvironmentAsItIs() {
    MockEnvironment environment = new MockEnvironment();

    new McpServerSwitch().postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getPropertySources().contains(McpServerSwitch.PROPERTY_SOURCE))
        .isFalse();
  }

  @Test
  void theFeatureOffOverridesEveryOtherSettingOfTheServer() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(Feature.EXTERNAL_ACCESS.enabledProperty(), "false")
            .withProperty(McpServerSwitch.SERVER_ENABLED, "true");

    new McpServerSwitch().postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty(McpServerSwitch.SERVER_ENABLED)).isEqualTo("false");
  }

  /** Without its entry in {@code META-INF/spring.factories} Boot would never run the switch. */
  @Test
  void bootFindsTheSwitchAmongTheEnvironmentPostProcessors() {
    assertThat(
            SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
                .load(
                    EnvironmentPostProcessor.class,
                    null,
                    // Boot's own processors need constructor arguments only Boot supplies.
                    SpringFactoriesLoader.FailureHandler.handleMessage((message, failure) -> {})))
        .hasAtLeastOneElementOfType(McpServerSwitch.class);
  }
}
