package io.opaa.mcp;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.stereotype.Component;

/**
 * Where the MCP server listens. The value comes from {@code
 * spring.ai.mcp.server.streamable-http.mcp-endpoint} - <b>also in the stateless mode</b>, which has
 * no properties group of its own however much the reference documentation suggests one (ADR-0035,
 * Entscheidung 1).
 *
 * <p>Everything that must agree with the path - the filter chain rule, the positive list and the
 * refusal style - derives it from this bean, so a changed path cannot leave the endpoint
 * unprotected behind a rule pointing elsewhere.
 */
@Component
public class McpEndpoint {

  private final String path;

  McpEndpoint(McpServerStreamableHttpProperties properties) {
    String configured = properties.getMcpEndpoint();
    if (configured == null || configured.isBlank() || !configured.startsWith("/")) {
      throw new IllegalStateException(
          "spring.ai.mcp.server.streamable-http.mcp-endpoint must be an absolute path, got: "
              + configured);
    }
    this.path = configured;
  }

  public String path() {
    return path;
  }

  /** Whether {@code request} is aimed at the MCP endpoint, whatever it presents. */
  public boolean matches(HttpServletRequest request) {
    return path.equals(request.getRequestURI());
  }
}
