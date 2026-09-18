package io.opaa.mcp;

import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.List;
import java.util.function.UnaryOperator;
import reactor.core.publisher.Mono;

/**
 * The transport the MCP server is built on: the WebMVC one, with two things changed that cannot be
 * configured (ADR-0035, Entscheidung 1 and 6).
 *
 * <p><b>It decides the announced protocol revisions</b>, instead of leaving them to the library's
 * default - they come from {@link McpProperties} and are what {@code initialize} negotiates
 * against.
 *
 * <p><b>It is the only place the server's request handler can be wrapped.</b> The server builds
 * that handler in its constructor and hands it to the transport; nothing reads it back. Taking it
 * here is therefore the seam for the per-request work the library does not do: a tool list per
 * token, the instructions of the running installation, and the refusal of an unknown revision.
 */
class OpaaMcpTransport implements McpStatelessServerTransport {

  private final McpStatelessServerTransport delegate;
  private final List<String> protocolVersions;
  private final UnaryOperator<McpStatelessServerHandler> decorator;

  OpaaMcpTransport(
      McpStatelessServerTransport delegate,
      List<String> protocolVersions,
      UnaryOperator<McpStatelessServerHandler> decorator) {
    this.delegate = delegate;
    this.protocolVersions = List.copyOf(protocolVersions);
    this.decorator = decorator;
  }

  @Override
  public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
    delegate.setMcpHandler(decorator.apply(mcpHandler));
  }

  @Override
  public List<String> protocolVersions() {
    return protocolVersions;
  }

  @Override
  public Mono<Void> closeGracefully() {
    return delegate.closeGracefully();
  }
}
