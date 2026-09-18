package io.opaa.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.CurrentUserArgumentResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * What one MCP request carries from the HTTP layer into the tools: the caller the channel's filter
 * chain authenticated, and the protocol version the client named per request.
 *
 * <p>The caller travels in the transport context, the mechanism the library provides for exactly
 * this. The <b>token id does not</b>: {@code ExternalAccessSearchScopeSource} reads it from {@code
 * RequestContextHolder}, and without it the effective view falls back to everything the person may
 * read. The tool call must therefore run on the request's own thread - which it does because the
 * stateless autoconfiguration switches on immediate execution in a servlet environment. Moving the
 * tools off that thread would widen the view silently; the scope tests of {@code
 * McpServerIntegrationTest} are what would go red.
 */
final class McpRequestContext {

  static final String CALLER = "opaa.caller";
  static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

  private McpRequestContext() {}

  static McpTransportContext of(ServerRequest request) {
    HttpServletRequest servletRequest = request.servletRequest();
    Map<String, Object> metadata = new LinkedHashMap<>();
    CurrentUser caller = CurrentUserArgumentResolver.callerOf(servletRequest);
    if (caller != null) {
      metadata.put(CALLER, caller);
    }
    String version = servletRequest.getHeader(PROTOCOL_VERSION_HEADER);
    if (version != null) {
      metadata.put(PROTOCOL_VERSION_HEADER, version);
    }
    return McpTransportContext.create(metadata);
  }

  /**
   * The caller of this request. Never {@code null} in production: the endpoint is only reachable
   * through the channel's filter chain, which authenticates before any handler runs.
   */
  static CurrentUser callerOf(McpTransportContext context) {
    Object caller = context == null ? null : context.get(CALLER);
    if (caller instanceof CurrentUser currentUser) {
      return currentUser;
    }
    throw new IllegalStateException(
        "MCP request reached a handler without an authenticated caller");
  }
}
