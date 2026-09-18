package io.opaa.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.opaa.common.TooManyRequestsException;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.search.SearchService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * The three per-request answers the library gives per server (#1721, ADR-0035, Umsetzungsrisiken 1
 * and 2). Everything else - {@code tools/call}, {@code ping}, notifications - passes straight
 * through.
 *
 * <ul>
 *   <li><b>{@code tools/list}</b> is answered here, because {@code McpStatelessAsyncServer} answers
 *       it from the server-wide list it was built with and never looks at the request. The
 *       descriptions are built from the caller's effective view, so two tokens with different views
 *       see different descriptions - and no library outside a view is ever named.
 *   <li><b>{@code initialize} carries the instructions of the running installation</b>, read per
 *       request, instead of the value the server was built with: {@code
 *       spring.ai.mcp.server.instructions} is bound once at startup, and this text is an
 *       administration setting that must take effect without a restart.
 *   <li><b>An unknown protocol revision is refused</b> instead of silently answered with the
 *       server's highest, which is what the library does (a WARN line and a successful answer). The
 *       refusal names the supported revisions, and every successful {@code initialize} carries them
 *       too - a legacy client's only way to learn them.
 * </ul>
 *
 * <p>The refusal is deliberately {@code INVALID_PARAMS} and deliberately <b>not</b> {@code
 * UnsupportedProtocolVersionError} ({@code -32022}): that code is the marker of a server speaking
 * the per-request negotiation of {@code 2026-07-28}. Sent by a server that does not, it would tell
 * a dual-era client to retry modern instead of falling back to the handshake that works.
 */
class OpaaMcpHandler implements McpStatelessServerHandler {

  /** Where a legacy client finds the revisions this installation speaks. */
  static final String SUPPORTED_VERSIONS_KEY = "io.opaa/supportedProtocolVersions";

  /**
   * JSON-RPC leaves {@code -32000}..{@code -32099} to the server. The exhausted quota gets one of
   * them rather than {@code INTERNAL_ERROR}: it is a refusal the caller can act on by waiting.
   */
  static final int QUOTA_EXCEEDED = -32000;

  private final McpStatelessServerHandler delegate;
  private final McpToolCatalog catalog;
  private final SearchService searchService;
  private final ExternalAccessSettingsService settings;
  private final List<String> protocolVersions;

  OpaaMcpHandler(
      McpStatelessServerHandler delegate,
      McpToolCatalog catalog,
      SearchService searchService,
      ExternalAccessSettingsService settings,
      List<String> protocolVersions) {
    this.delegate = delegate;
    this.catalog = catalog;
    this.searchService = searchService;
    this.settings = settings;
    this.protocolVersions = List.copyOf(protocolVersions);
  }

  @Override
  public Mono<JSONRPCResponse> handleRequest(
      McpTransportContext transportContext, JSONRPCRequest request) {
    if (McpSchema.METHOD_TOOLS_LIST.equals(request.method())) {
      return Mono.fromCallable(
          () -> {
            try {
              return JSONRPCResponse.result(request.id(), toolsFor(transportContext));
            } catch (TooManyRequestsException e) {
              // The listing counts against the quota like every other call; an exhausted quota is
              // a named refusal here, not the 500 an escaping exception would become.
              return JSONRPCResponse.error(
                  request.id(), new JSONRPCError(QUOTA_EXCEEDED, e.getMessage()));
            }
          });
    }
    if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
      String requested = requestedVersion(request.params());
      if (requested != null && !protocolVersions.contains(requested)) {
        return Mono.just(JSONRPCResponse.error(request.id(), unsupportedVersion(requested)));
      }
      return delegate.handleRequest(transportContext, request).map(this::withInstallationState);
    }
    return delegate.handleRequest(transportContext, request);
  }

  @Override
  public Mono<Void> handleNotification(
      McpTransportContext transportContext, McpSchema.JSONRPCNotification notification) {
    return delegate.handleNotification(transportContext, notification);
  }

  private ListToolsResult toolsFor(McpTransportContext transportContext) {
    return new ListToolsResult(
        catalog.toolsFor(
            searchService.librariesForDescription(McpRequestContext.callerOf(transportContext))),
        null,
        null);
  }

  /** The instructions of this moment, plus the revision list every client may read. */
  private JSONRPCResponse withInstallationState(JSONRPCResponse response) {
    if (!(response.result() instanceof InitializeResult result)) {
      return response;
    }
    Map<String, Object> meta = new LinkedHashMap<>();
    if (result.meta() != null) {
      meta.putAll(result.meta());
    }
    meta.put(SUPPORTED_VERSIONS_KEY, protocolVersions);
    return JSONRPCResponse.result(
        response.id(),
        new InitializeResult(
            result.protocolVersion(),
            result.capabilities(),
            result.serverInfo(),
            settings.current().values().serverInstructions(),
            meta));
  }

  private JSONRPCError unsupportedVersion(String requested) {
    return new JSONRPCError(
        McpSchema.ErrorCodes.INVALID_PARAMS,
        "Die Protokollfassung „"
            + requested
            + "“ wird von dieser Installation nicht bedient. Bedient werden: "
            + String.join(", ", protocolVersions)
            + ".",
        Map.of(SUPPORTED_VERSIONS_KEY, protocolVersions));
  }

  private static String requestedVersion(Object params) {
    if (params instanceof Map<?, ?> map && map.get("protocolVersion") != null) {
      return map.get("protocolVersion").toString();
    }
    return null;
  }
}
