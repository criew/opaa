package io.opaa.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public class RateLimitFilter extends OncePerRequestFilter {

  private static final String GLOBAL_KEY = "__global__";
  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

  private final Map<String, RateLimitService> perIpLimiters;
  private final Map<String, RateLimitService> globalLimiters;
  private final ObjectMapper objectMapper;

  public RateLimitFilter(
      Map<String, RateLimitService> perIpLimiters,
      Map<String, RateLimitService> globalLimiters,
      ObjectMapper objectMapper) {
    this.perIpLimiters = perIpLimiters;
    this.globalLimiters = globalLimiters;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    String clientIp = resolveClientIp(request);

    for (var entry : globalLimiters.entrySet()) {
      if (path.startsWith(entry.getKey())) {
        if (!entry.getValue().isAllowed(GLOBAL_KEY)) {
          log.warn("Global rate limit exceeded on {} (request from {})", path, clientIp);
          writeRateLimitResponse(response);
          return;
        }
        break;
      }
    }

    for (var entry : perIpLimiters.entrySet()) {
      if (path.startsWith(entry.getKey())) {
        if (!entry.getValue().isAllowed(clientIp)) {
          log.warn("Rate limit exceeded for {} on {}", clientIp, path);
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
    objectMapper.writeValue(response.getOutputStream(), body);
  }
}
