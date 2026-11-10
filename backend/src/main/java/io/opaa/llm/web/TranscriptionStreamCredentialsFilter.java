package io.opaa.llm.web;

import io.opaa.auth.DevAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Hands the credentials of a WebSocket upgrade to the security chain as ordinary headers. A browser
 * cannot set headers on a WebSocket, so the SPA offers its bearer token as a second subprotocol
 * {@code opaa-bearer.<token>} next to {@link #PROTOCOL} (in the {@code dev} profile the user as
 * {@code opaa-dev-user.<subject>}). This filter turns them into {@code Authorization} and the dev
 * user header - never overriding one the request carries - and removes them from {@code
 * Sec-WebSocket-Protocol}, so the handshake can only ever select and answer {@link #PROTOCOL} and
 * the token is not echoed back.
 */
class TranscriptionStreamCredentialsFilter extends OncePerRequestFilter {

  /** The one subprotocol of the endpoint. */
  static final String PROTOCOL = "opaa-transcription.v1";

  static final String BEARER_PREFIX = "opaa-bearer.";
  static final String DEV_USER_PREFIX = "opaa-dev-user.";

  /** Before Spring Security's filter chain (order -100). */
  static final int ORDER = -110;

  private static final String PROTOCOL_HEADER = "Sec-WebSocket-Protocol";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Enumeration<String> offered = request.getHeaders(PROTOCOL_HEADER);
    if (offered == null || !offered.hasMoreElements()) {
      chain.doFilter(request, response);
      return;
    }
    List<String> protocols = new ArrayList<>();
    Map<String, String> added = new LinkedHashMap<>();
    for (String header : Collections.list(offered)) {
      for (String value : header.split(",")) {
        String protocol = value.strip();
        if (protocol.startsWith(BEARER_PREFIX)) {
          added.putIfAbsent(
              HttpHeaders.AUTHORIZATION, "Bearer " + protocol.substring(BEARER_PREFIX.length()));
        } else if (protocol.startsWith(DEV_USER_PREFIX)) {
          added.putIfAbsent(
              DevAuthFilter.DEV_USER_HEADER, protocol.substring(DEV_USER_PREFIX.length()));
        } else if (!protocol.isEmpty()) {
          protocols.add(protocol);
        }
      }
    }
    added.keySet().removeIf(name -> request.getHeader(name) != null);
    chain.doFilter(new Upgrade(request, added, protocols), response);
  }

  /** The request with the credentials as headers and only the plain subprotocols left. */
  private static final class Upgrade extends HttpServletRequestWrapper {

    private final Map<String, String> added;
    private final List<String> protocols;

    Upgrade(HttpServletRequest request, Map<String, String> added, List<String> protocols) {
      super(request);
      this.added = added;
      this.protocols = protocols;
    }

    private String addedValue(String name) {
      for (Map.Entry<String, String> entry : added.entrySet()) {
        if (entry.getKey().equalsIgnoreCase(name)) {
          return entry.getValue();
        }
      }
      return null;
    }

    private static boolean isProtocolHeader(String name) {
      return PROTOCOL_HEADER.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public String getHeader(String name) {
      if (isProtocolHeader(name)) {
        return protocols.isEmpty() ? null : String.join(", ", protocols);
      }
      String value = addedValue(name);
      return value != null ? value : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      if (isProtocolHeader(name)) {
        return Collections.enumeration(
            protocols.isEmpty() ? List.of() : List.of(String.join(", ", protocols)));
      }
      String value = addedValue(name);
      return value != null ? Collections.enumeration(List.of(value)) : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      List<String> names = new ArrayList<>();
      for (String name : Collections.list(super.getHeaderNames())) {
        if (!isProtocolHeader(name) || !protocols.isEmpty()) {
          names.add(name);
        }
      }
      for (String name : added.keySet()) {
        if (names.stream().noneMatch(name::equalsIgnoreCase)) {
          names.add(name);
        }
      }
      return Collections.enumeration(names);
    }
  }
}
