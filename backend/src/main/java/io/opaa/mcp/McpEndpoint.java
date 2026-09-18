package io.opaa.mcp;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
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
  private final RequestMatcher matcher;

  McpEndpoint(McpServerStreamableHttpProperties properties) {
    String configured = properties.getMcpEndpoint();
    if (configured == null || configured.isBlank() || !configured.startsWith("/")) {
      throw new IllegalStateException(
          "spring.ai.mcp.server.streamable-http.mcp-endpoint must be an absolute path, got: "
              + configured);
    }
    this.path = configured;
    this.matcher = PathPatternRequestMatcher.withDefaults().matcher(configured);
  }

  public String path() {
    return path;
  }

  /** The one matcher for this path - for the filter chain and for {@link #matches}. */
  public RequestMatcher matcher() {
    return matcher;
  }

  /**
   * Whether {@code request} is aimed at the MCP endpoint, whatever it presents.
   *
   * <p>Decided by the same {@code PathPatternParser} the filter chain and the library's route use,
   * never by the raw {@code getRequestURI()}: that string still carries matrix variables and
   * percent escapes, so {@code /mcp;x=1} and {@code /%6Dcp} would be served by the endpoint while
   * the version check and the refusal style of this package considered them a different path.
   */
  public boolean matches(HttpServletRequest request) {
    return matcher.matches(request);
  }
}
