package io.opaa.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.search.SearchService;
import java.util.List;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the MCP server (#1721, ADR-0035). Two beans replace what the starter would have built,
 * because neither can be configured:
 *
 * <ul>
 *   <li>the WebMVC transport, so every request carries its authenticated caller into the tools
 *       through the transport context - the starter builds it with an empty extractor;
 *   <li>a wrapping transport, the only seam at which the server's request handler can be decorated
 *       ({@link OpaaMcpTransport}), and the place the announced protocol revisions come from.
 * </ul>
 *
 * <p>Everything else stays the starter's: the router function, the sync server, the capabilities.
 * Tools are the only capability offered - resources, prompts and completions are separate exposure
 * surfaces with their own permission question, and registering one is the decision to expose it.
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpServerConfiguration {

  @Bean
  WebMvcStatelessServerTransport webMvcStatelessServerTransport(
      @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
      McpServerStreamableHttpProperties properties) {
    return WebMvcStatelessServerTransport.builder()
        .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
        .messageEndpoint(properties.getMcpEndpoint())
        .contextExtractor(McpRequestContext::of)
        .build();
  }

  @Bean
  @Primary
  McpStatelessServerTransport opaaMcpTransport(
      WebMvcStatelessServerTransport delegate,
      McpProperties properties,
      McpToolCatalog catalog,
      SearchService searchService,
      ExternalAccessSettingsService settings) {
    return new OpaaMcpTransport(
        delegate,
        properties.protocolVersions(),
        handler ->
            new OpaaMcpHandler(
                handler, catalog, searchService, settings, properties.protocolVersions()));
  }

  @Bean
  List<SyncToolSpecification> mcpToolSpecifications(McpTools tools) {
    return tools.specifications();
  }

  /**
   * Registered as an ordinary servlet filter, which runs after the security chain: a caller without
   * a usable token is refused before it learns which revisions this installation speaks.
   */
  @Bean
  FilterRegistrationBean<McpProtocolVersionFilter> mcpProtocolVersionFilter(
      McpEndpoint endpoint, McpProperties properties) {
    FilterRegistrationBean<McpProtocolVersionFilter> registration =
        new FilterRegistrationBean<>(
            new McpProtocolVersionFilter(endpoint, properties.protocolVersions()));
    registration.addUrlPatterns(endpoint.path());
    return registration;
  }
}
