package io.opaa.auth;

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
 * SearchHitAssembler}, {@code PassageFetchService}). The MCP server of #1721 adds {@code /mcp}.
 */
@Component
public class ExternalAccessPathAllowlist {

  private final List<RequestMatcher> allowed;

  public ExternalAccessPathAllowlist() {
    this(
        List.of(
            post("/api/v1/search"), get("/api/v1/search/libraries"), get("/api/v1/search/hits/*")));
  }

  ExternalAccessPathAllowlist(List<RequestMatcher> allowed) {
    this.allowed = List.copyOf(allowed);
  }

  /** The matchers the filter chain authorises; everything else is refused. */
  public List<RequestMatcher> matchers() {
    return allowed;
  }

  private static RequestMatcher get(String pattern) {
    return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, pattern);
  }

  private static RequestMatcher post(String pattern) {
    return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, pattern);
  }
}
