package io.opaa.ratelimit;

import io.opaa.common.TooManyRequestsException;
import io.opaa.observability.RateLimitMetrics;
import io.opaa.ratelimit.RateLimitService.Decision;
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
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
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
 * their client budget take a token from - and a refusal is {@code 429} with {@code Retry-After} and
 * the one German message. The filter runs before the security chain; which requests keep the tokens
 * they took once answered is the rule's {@link Counting}. Rules match the decoded path within the
 * application ({@link UrlPathHelper#getPathWithinApplication}), the same path the handler mapping
 * sees: a percent-encoded spelling or a forwarded prefix ({@code X-Forwarded-Prefix}, which moves
 * the prefix into the context path) cannot reach a handler past its rule. The client address comes
 * from {@link ClientIpResolver} (ADR-0033, Entscheidung 9), never from a header a client can set
 * itself; IPv6 clients are keyed by their /64, because a single host commonly owns a whole /64.
 * CORS preflights are not counted.
 */
public class RateLimitFilter extends OncePerRequestFilter {

  private static final String GLOBAL_KEY = "__global__";
  private static final String UNKNOWN_CLIENT_KEY = "unknown";
  private static final Pattern LIBRARY_ID =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
  static final String RATE_LIMIT_MESSAGE = TooManyRequestsException.MESSAGE;

  /** The answer of every endpoint that cannot be switched off. */
  public static final BooleanSupplier ALWAYS_SERVED = () -> true;

  /**
   * Which of the requests a rule admitted keep their tokens once they are answered. A request
   * answered {@code 401} did not authenticate.
   */
  public enum Counting {
    /**
     * Every admitted request keeps its tokens - for the endpoints of the sign-in itself, whose
     * global ceiling counts the failed attempts it warns about (ADR-0033, Entscheidung 9).
     */
    EVERY_REQUEST,

    /**
     * Behind the sign-in: a request answered {@code 401} gives its global token back, so strangers
     * cannot exhaust the ceiling shared by everyone signed in.
     */
    AUTHENTICATED,

    /**
     * Reachable without a session, the handler authenticates: as {@link #AUTHENTICATED}, and a
     * request answered {@code 401} also counts under the client's address alone - every library id
     * a stranger names draws on one budget per address, and while it is used up the address is
     * refused for every library.
     */
    AUTHENTICATED_BY_HANDLER
  }

  /**
   * One rule. {@code pathPattern} is a regular expression matched against the decoded path within
   * the application with {@link Matcher#find()} - callers anchor with {@code ^} (and, where the
   * match must not consume an unrelated sub-path, {@code $}) themselves. If the pattern contains a
   * capture group (as the per-library indexing rule does), it captures a library id: a well-formed
   * UUID, in any letter case, is appended to the client key ({@code clientKey + ":" + libraryId});
   * anything else counts under the client key alone, so made-up segments buy no fresh budget. The
   * global limiter always uses one fixed key. {@code global} may be {@code null} for a rule without
   * a global ceiling.
   *
   * <p>{@code served} answers, per request, whether the endpoint behind the rule exists at all
   * right now; it is {@link #ALWAYS_SERVED} for every rule whose endpoint cannot be switched off. A
   * rule that reports its endpoint as unserved is matched but <b>not counted</b>: such a request is
   * answered exactly like an unknown route, and an unknown route has no budget - a 429 here against
   * a 401 there would be the one difference a probe would find.
   *
   * @param name the stable label of the rule in logs and metrics
   */
  public record Rule(
      String name,
      String pathPattern,
      RateLimitService perClient,
      RateLimitService global,
      BooleanSupplier served,
      Counting counting) {

    public Rule(
        String name, String pathPattern, RateLimitService perClient, RateLimitService global) {
      this(name, pathPattern, perClient, global, ALWAYS_SERVED, Counting.EVERY_REQUEST);
    }

    public Rule(
        String name,
        String pathPattern,
        RateLimitService perClient,
        RateLimitService global,
        BooleanSupplier served) {
      this(name, pathPattern, perClient, global, served, Counting.EVERY_REQUEST);
    }

    public Rule(
        String name,
        String pathPattern,
        RateLimitService perClient,
        RateLimitService global,
        Counting counting) {
      this(name, pathPattern, perClient, global, ALWAYS_SERVED, counting);
    }
  }

  private record CompiledRule(
      String name,
      Pattern path,
      RateLimitService perClient,
      RateLimitService global,
      BooleanSupplier served,
      Counting counting) {}

  /** The tokens an admitted request took that its answer may still give back. */
  private record Admission(
      CompiledRule rule, String client, boolean addressTaken, boolean globalTaken) {}

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
                        rule.global(),
                        rule.served(),
                        rule.counting()))
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
    Admission admission = null;
    for (CompiledRule rule : rules) {
      Matcher matcher = rule.path().matcher(path);
      if (!matcher.find()) {
        continue;
      }
      if (rule.served().getAsBoolean()) {
        admission = admit(rule, matcher, request, response, path);
        if (admission == null) {
          return;
        }
      }
      break;
    }
    try {
      filterChain.doFilter(request, response);
    } finally {
      if (admission != null) {
        settle(admission, response.getStatus());
      }
    }
  }

  /**
   * Takes the tokens of one request, or answers {@code 429} and returns {@code null}. A request of
   * an {@link Counting#AUTHENTICATED_BY_HANDLER} rule that names a library also takes a token of
   * its address alone, which {@link #settle} gives back once the request authenticated.
   */
  private Admission admit(
      CompiledRule rule,
      Matcher matcher,
      HttpServletRequest request,
      HttpServletResponse response,
      String path)
      throws IOException {
    String client = bucketKey(clientIpResolver.resolve(request));
    String key = clientKey(client, matcher);
    boolean addressTaken =
        rule.counting() == Counting.AUTHENTICATED_BY_HANDLER && !key.equals(client);
    for (String clientScope : addressTaken ? List.of(key, client) : List.of(key)) {
      Decision decision = rule.perClient().tryAcquire(clientScope);
      if (!decision.allowed()) {
        metrics.recordRejected(rule.name(), "client");
        log.info("Rate limit {} exceeded for {} on {}", rule.name(), clientScope, path);
        writeRateLimitResponse(response, decision.retryAfterSeconds());
        return null;
      }
    }
    if (rule.global() != null) {
      Decision global = rule.global().tryAcquire(GLOBAL_KEY);
      if (!global.allowed()) {
        metrics.recordRejected(rule.name(), "global");
        log.warn(
            "Global rate limit {} exceeded on {} (request from {})", rule.name(), path, client);
        writeRateLimitResponse(response, global.retryAfterSeconds());
        return null;
      }
    }
    return new Admission(rule, client, addressTaken, rule.global() != null);
  }

  /** Gives back what the answer shows the request did not owe - see {@link Counting}. */
  private static void settle(Admission admission, int status) {
    CompiledRule rule = admission.rule();
    boolean authenticated = status != HttpStatus.UNAUTHORIZED.value();
    if (admission.globalTaken() && !authenticated && rule.counting() != Counting.EVERY_REQUEST) {
      rule.global().release(GLOBAL_KEY);
    }
    if (admission.addressTaken() && authenticated) {
      rule.perClient().release(admission.client());
    }
  }

  /**
   * The per-client key: the client alone, or with the library id the rule captured - only a
   * well-formed UUID, lower-cased, so another spelling of one id is the same library.
   */
  static String clientKey(String client, Matcher matcher) {
    if (matcher.groupCount() < 1) {
      return client;
    }
    String segment = matcher.group(1);
    if (segment == null || !LIBRARY_ID.matcher(segment).matches()) {
      return client;
    }
    return client + ":" + segment.toLowerCase(Locale.ROOT);
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
