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
 * this, and carries its access token with it ({@link CurrentUser#accessTokenId()}). Nothing a tool
 * needs is read from the request's thread, so the effective view and the quota hold on whichever
 * thread the tool runs.
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
   * The caller of this request, always one that arrived on an access token. Every MCP call is a
   * token call, so a caller without a token - or none at all - is refused rather than served the
   * person's view; in production the channel's filter chain rules both out before any handler runs.
   */
  static CurrentUser callerOf(McpTransportContext context) {
    Object caller = context == null ? null : context.get(CALLER);
    if (!(caller instanceof CurrentUser currentUser)) {
      throw new IllegalStateException(
          "MCP request reached a handler without an authenticated caller");
    }
    if (currentUser.accessTokenId() == null) {
      throw new IllegalStateException("MCP request reached a handler without an access token");
    }
    return currentUser;
  }
}
