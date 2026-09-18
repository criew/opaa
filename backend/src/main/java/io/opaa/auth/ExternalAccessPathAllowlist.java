package io.opaa.auth;

import io.opaa.mcp.McpEndpoint;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * What an access-token call may reach - <b>a positive list, never an exclusion list</b> (ADR-0035;
 * the specification calls this the security-critical part of the channel). An exclusion list would
 * be silently incomplete the moment someone adds an endpoint and forgets it; a positive list is
 * silently too strict, which is a bug report, not a breach.
 *
 * <p>The three reading paths of #1720 and nothing else: searching, the text behind one hit, and the
 * list of libraries the token may search. Everything else - administration, indexing, upload,
 * rights, and the whole web interface - is refused with {@code 403}.
 *
 * <p>Two endpoints are deliberately <b>not</b> on it. {@code GET /api/v1/libraries} answers with
 * everything the person may read rather than with the effective view of the token, so it would be
 * the first leak past exactly the intersection this channel promises. {@code GET
 * /api/v1/documents/&#123;id&#125;/content} hands out the original file; the channel hands out
 * passages, which is why a token call is never offered a download link in the first place ({@code
 * SearchHitAssembler}, {@code PassageFetchService}).
 *
 * <p>{@code /mcp} (#1721) is on the list twice over: authorised for {@code POST} like the reading
 * paths, and <b>owned</b> by this channel for every method - see {@link #ownedMatchers()}. Its path
 * comes from {@link McpEndpoint}, the same bean the endpoint itself listens on, so a changed path
 * cannot leave the endpoint unprotected behind a rule pointing elsewhere.
 */
@Component
public class ExternalAccessPathAllowlist {

  private final List<RequestMatcher> allowed;
  private final List<RequestMatcher> owned;

  public ExternalAccessPathAllowlist(McpEndpoint mcpEndpoint) {
    this(
        List.of(
            post("/api/v1/search"),
            get("/api/v1/search/libraries"),
            get("/api/v1/search/hits/*"),
            post(mcpEndpoint.path())),
        List.of(PathPatternRequestMatcher.withDefaults().matcher(mcpEndpoint.path())));
  }

  ExternalAccessPathAllowlist(List<RequestMatcher> allowed) {
    this(allowed, List.of());
  }

  ExternalAccessPathAllowlist(List<RequestMatcher> allowed, List<RequestMatcher> owned) {
    this.allowed = List.copyOf(allowed);
    this.owned = List.copyOf(owned);
  }

  /** The matchers the filter chain authorises; everything else is refused. */
  public List<RequestMatcher> matchers() {
    return allowed;
  }

  /**
   * The paths this channel owns outright: they are routed into its filter chain <b>even without a
   * bearer value</b>, so no other chain can serve them anonymously. Only the MCP endpoint is one -
   * the Spring AI starter registers it unauthenticated, and under {@code local,dev} the development
   * filter would otherwise authenticate an anonymous call to it as the development user.
   */
  public List<RequestMatcher> ownedMatchers() {
    return owned;
  }

  private static RequestMatcher get(String pattern) {
    return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, pattern);
  }

  private static RequestMatcher post(String pattern) {
    return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, pattern);
  }
}
