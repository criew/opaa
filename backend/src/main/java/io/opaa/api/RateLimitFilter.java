package io.opaa.api;

import io.opaa.api.RateLimitService.Decision;
import io.opaa.common.TooManyRequestsException;
import io.opaa.observability.RateLimitMetrics;
import io.opaa.security.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The path-keyed rate limits: the first {@link Rule} whose pattern matches the request path is
 * applied - the client's own budget first, then the global ceiling, which only requests that passed
 * their client budget consume (one address can never exhaust it for everyone) - and a refusal is
 * {@code 429} with {@code Retry-After} and the one German message. Rules match the decoded path
 * within the application ({@link UrlPathHelper#getPathWithinApplication}), the same path the
 * handler mapping sees: a percent-encoded spelling or a forwarded prefix ({@code
 * X-Forwarded-Prefix}, which moves the prefix into the context path) cannot reach a handler past
 * its rule. The client address comes from {@link ClientIpResolver} (ADR-0033, Entscheidung 9),
 * never from a header a client can set itself; IPv6 clients are keyed by their /64, because a
 * single host commonly owns a whole /64. CORS preflights are not counted.
 */
public class RateLimitFilter extends OncePerRequestFilter {

  private static final String GLOBAL_KEY = "__global__";
  private static final String UNKNOWN_CLIENT_KEY = "unknown";
  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
  static final String RATE_LIMIT_MESSAGE = TooManyRequestsException.MESSAGE;

  /**
   * One rule. {@code pathPattern} is a regular expression matched against the decoded path within
   * the application with {@link Matcher#find()} - callers anchor with {@code ^} (and, where the
   * match must not consume an unrelated sub-path, {@code $}) themselves. If the pattern contains a
   * capture group (as the per-library indexing rule does), the captured value is appended to the
   * client key to form the per-client key ({@code clientKey + ":" + libraryId}); the global limiter
   * always uses one fixed key. {@code global} may be {@code null} for a rule without a global
   * ceiling.
   *
   * @param name the stable label of the rule in logs and metrics
   */
  public record Rule(
      String name, String pathPattern, RateLimitService perClient, RateLimitService global) {}

  private record CompiledRule(
      String name, Pattern path, RateLimitService perClient, RateLimitService global) {}

  private final List<CompiledRule> rules;
  private final ClientIpResolver clientIpResolver;
  private final RateLimitMetrics metrics;
  private final JsonMapper jsonMapper;

  public RateLimitFilter(
      List<Rule> rules,
      ClientIpResolver clientIpResolver,
      RateLimitMetrics metrics,
      JsonMapper jsonMapper) {
    this.rules =
        rules.stream()
            .map(
                rule ->
                    new CompiledRule(
                        rule.name(),
                        Pattern.compile(rule.pathPattern()),
                        rule.perClient(),
                        rule.global()))
            .toList();
    this.clientIpResolver = clientIpResolver;
    this.metrics = metrics;
    this.jsonMapper = jsonMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String path = pathWithinApplication(request);
    for (CompiledRule rule : rules) {
      Matcher matcher = rule.path().matcher(path);
      if (!matcher.find()) {
        continue;
      }
      String client = bucketKey(clientIpResolver.resolve(request));
      String key = matcher.groupCount() >= 1 ? client + ":" + matcher.group(1) : client;
      Decision decision = rule.perClient().tryAcquire(key);
      if (!decision.allowed()) {
        metrics.recordRejected(rule.name(), "client");
        log.info("Rate limit {} exceeded for {} on {}", rule.name(), key, path);
        writeRateLimitResponse(response, decision.retryAfterSeconds());
        return;
      }
      if (rule.global() != null) {
        Decision global = rule.global().tryAcquire(GLOBAL_KEY);
        if (!global.allowed()) {
          metrics.recordRejected(rule.name(), "global");
          log.warn(
              "Global rate limit {} exceeded on {} (request from {})", rule.name(), path, client);
          writeRateLimitResponse(response, global.retryAfterSeconds());
          return;
        }
      }
      break;
    }
    filterChain.doFilter(request, response);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !pathWithinApplication(request).startsWith("/api/")
        || "OPTIONS".equalsIgnoreCase(request.getMethod());
  }

  /** The decoded path below the context path - what the handler mapping matches against. */
  static String pathWithinApplication(HttpServletRequest request) {
    return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
  }

  /**
   * The bucket key of a client address: IPv4 as is, IPv6 reduced to its /64 (a host usually owns
   * the whole prefix, so "per address" would be no limit at all), an unknown address as one shared
   * key.
   */
  static String bucketKey(String clientAddress) {
    if (clientAddress == null || clientAddress.isBlank()) {
      return UNKNOWN_CLIENT_KEY;
    }
    if (clientAddress.indexOf(':') < 0) {
      return clientAddress;
    }
    try {
      byte[] address = InetAddress.getByName(clientAddress).getAddress();
      if (address.length != 16) {
        return clientAddress;
      }
      Arrays.fill(address, 8, 16, (byte) 0);
      return InetAddress.getByAddress(address).getHostAddress() + "/64";
    } catch (UnknownHostException | IllegalArgumentException notALiteral) {
      return clientAddress;
    }
  }

  private void writeRateLimitResponse(HttpServletResponse response, long retryAfterSeconds)
      throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    var body =
        Map.of(
            "error", RATE_LIMIT_MESSAGE,
            "status", HttpStatus.TOO_MANY_REQUESTS.value(),
            "timestamp", Instant.now().toString());
    jsonMapper.writeValue(response.getOutputStream(), body);
  }
}
