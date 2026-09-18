package io.opaa.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The half of the version promise that a JSON-RPC answer cannot keep (ADR-0035, Entscheidung 6b): a
 * request naming a revision this installation does not serve in its {@code MCP-Protocol-Version}
 * header is refused with {@code 400} before it reaches the server.
 *
 * <p>That header is how the specification from {@code 2026-07-28} negotiates - per request, without
 * an {@code initialize} at all. Without this check such a client would be served under a legacy
 * meaning of an ambiguous method, which is exactly the silent divergence the promise rules out.
 *
 * <p><b>The body is plain text and carries no JSON-RPC error object.</b> A dual-era client reads a
 * {@code 400} and looks for a modern error in the body: finding one, it retries with the revisions
 * listed there; finding none, it falls back to the {@code initialize} handshake - the way that
 * works here. The modern error form belongs to a server that speaks the modern negotiation.
 */
class McpProtocolVersionFilter extends OncePerRequestFilter {

  private final McpEndpoint endpoint;
  private final List<String> protocolVersions;

  McpProtocolVersionFilter(McpEndpoint endpoint, List<String> protocolVersions) {
    this.endpoint = endpoint;
    this.protocolVersions = List.copyOf(protocolVersions);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !endpoint.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requested = request.getHeader(McpRequestContext.PROTOCOL_VERSION_HEADER);
    if (requested == null || protocolVersions.contains(requested)) {
      filterChain.doFilter(request, response);
      return;
    }
    response.setStatus(HttpStatus.BAD_REQUEST.value());
    response.setContentType(MediaType.TEXT_PLAIN_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            "Die Protokollfassung „"
                + requested
                + "“ wird von dieser Installation nicht bedient. Bedient werden: "
                + String.join(", ", protocolVersions)
                + ".");
  }
}
