package io.opaa.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

public class RateLimitFilter extends OncePerRequestFilter {

  private static final String GLOBAL_KEY = "__global__";
  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

  private final Map<Pattern, RateLimitService> perIpLimiters;
  private final Map<Pattern, RateLimitService> globalLimiters;
  private final JsonMapper jsonMapper;

  /**
   * {@code perIpLimiters}/{@code globalLimiters} are keyed by a regular expression matched against
   * the request path with {@link java.util.regex.Matcher#find()} - callers anchor with {@code ^}
   * (and, where the match must not consume an unrelated sub-path, {@code $}) themselves, the same
   * way {@code "/api/v1/query"} used to behave as an implicit prefix match. This lets a single rule
   * target a path that carries a variable segment - e.g. {@code
   * "^/api/v1/libraries/([^/]+)/indexing$"} for the per-library indexing trigger (#478) - which a
   * plain {@code String#startsWith} could not express without also matching every other
   * sub-resource under {@code /api/v1/libraries/{libraryId}/...}.
   *
   * <p>If a {@code perIpLimiters} pattern contains a capture group (as the per-library indexing
   * rule does), the captured value is appended to the client IP to form the rate-limit key (e.g.
   * {@code clientIp + ":" + libraryId}). Without this, all clients would share a single bucket per
   * IP across every library, and a client who just triggered indexing for one library would be
   * blocked from triggering it for a different one. {@code globalLimiters} are unaffected - they
   * always use the fixed {@link #GLOBAL_KEY}, since they are meant to cap total request volume
   * across all libraries.
   */
  public RateLimitFilter(
      Map<String, RateLimitService> perIpLimiters,
      Map<String, RateLimitService> globalLimiters,
      JsonMapper jsonMapper) {
    this.perIpLimiters = compile(perIpLimiters);
    this.globalLimiters = compile(globalLimiters);
    this.jsonMapper = jsonMapper;
  }

  private static Map<Pattern, RateLimitService> compile(Map<String, RateLimitService> source) {
    Map<Pattern, RateLimitService> compiled = new LinkedHashMap<>();
    source.forEach((pattern, limiter) -> compiled.put(Pattern.compile(pattern), limiter));
    return compiled;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    String clientIp = resolveClientIp(request);

    for (var entry : globalLimiters.entrySet()) {
      if (entry.getKey().matcher(path).find()) {
        if (!entry.getValue().isAllowed(GLOBAL_KEY)) {
          log.warn("Global rate limit exceeded on {} (request from {})", path, clientIp);
          writeRateLimitResponse(response);
          return;
        }
        break;
      }
    }

    for (var entry : perIpLimiters.entrySet()) {
      var matcher = entry.getKey().matcher(path);
      if (matcher.find()) {
        String key = matcher.groupCount() >= 1 ? clientIp + ":" + matcher.group(1) : clientIp;
        if (!entry.getValue().isAllowed(key)) {
          log.warn("Rate limit exceeded for {} on {}", key, path);
          writeRateLimitResponse(response);
          return;
        }
        break;
      }
    }

    filterChain.doFilter(request, response);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/");
  }

  private String resolveClientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  private void writeRateLimitResponse(HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    var body =
        Map.of(
            "error", "Rate limit exceeded. Please try again later.",
            "status", HttpStatus.TOO_MANY_REQUESTS.value(),
            "timestamp", Instant.now().toString());
    jsonMapper.writeValue(response.getOutputStream(), body);
  }
}
