package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.RateLimitFilter.Rule;
import io.opaa.api.RateLimitService.Decision;
import io.opaa.observability.RateLimitMetrics;
import io.opaa.security.TrustedProxyClientIpResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class RateLimitFilterTest {

  private static final Decision ALLOWED = Decision.allow();
  private static final Decision REJECTED = Decision.reject(17);

  private static final String QUERY = "^/api/v1/query";
  private static final String INDEXING = "^/api/v1/libraries/([^/]+)/indexing$";
  // #514/PR #537 review, finding 3: mirrors RateLimitConfiguration's own registration of the
  // probe endpoints - the connection test and the two listings share one bucket.
  private static final String SOURCE_TEST =
      "^/api/v1/libraries/(?:source-test|confluence/spaces|s3/buckets)$";
  private static final String WEBHOOK = "^/api/v1/libraries/([^/]+)/confluence-webhook$";
  private static final String LOGIN = "^/api/v1/auth/local/login$";

  private RateLimitFilter filter;
  private RateLimitService queryLimiter;
  private RateLimitService indexingLimiter;
  private RateLimitService sourceTestLimiter;
  private RateLimitService globalQueryLimiter;
  private RateLimitService globalIndexingLimiter;
  private RateLimitService globalSourceTestLimiter;
  private RateLimitService webhookLimiter;
  private RateLimitService globalWebhookLimiter;
  private RateLimitService loginLimiter;
  private RateLimitService globalLoginLimiter;
  private SimpleMeterRegistry meterRegistry;
  private JsonMapper jsonMapper;

  @BeforeEach
  void setUp() {
    queryLimiter = mock(RateLimitService.class);
    indexingLimiter = mock(RateLimitService.class);
    sourceTestLimiter = mock(RateLimitService.class);
    webhookLimiter = mock(RateLimitService.class);
    globalWebhookLimiter = mock(RateLimitService.class);
    globalQueryLimiter = mock(RateLimitService.class);
    globalIndexingLimiter = mock(RateLimitService.class);
    globalSourceTestLimiter = mock(RateLimitService.class);
    loginLimiter = mock(RateLimitService.class);
    globalLoginLimiter = mock(RateLimitService.class);
    meterRegistry = new SimpleMeterRegistry();
    jsonMapper = JsonMapper.builder().build();

    for (RateLimitService global :
        List.of(
            globalQueryLimiter,
            globalIndexingLimiter,
            globalSourceTestLimiter,
            globalWebhookLimiter,
            globalLoginLimiter)) {
      when(global.tryAcquire(anyString())).thenReturn(ALLOWED);
    }

    filter = filter(List.of(), defaultRules());
  }

  private List<Rule> defaultRules() {
    return List.of(
        new Rule("query", QUERY, queryLimiter, globalQueryLimiter),
        new Rule("indexing", INDEXING, indexingLimiter, globalIndexingLimiter),
        new Rule("source-test", SOURCE_TEST, sourceTestLimiter, globalSourceTestLimiter),
        new Rule("webhook", WEBHOOK, webhookLimiter, globalWebhookLimiter),
        new Rule("local-auth-login", LOGIN, loginLimiter, globalLoginLimiter));
  }

  private RateLimitFilter filter(List<String> trustedProxies, List<Rule> rules) {
    return new RateLimitFilter(
        rules,
        new TrustedProxyClientIpResolver(trustedProxies),
        new RateLimitMetrics(meterRegistry),
        jsonMapper);
  }

  private double rejected(String limit, String scope) {
    return meterRegistry
        .counter(RateLimitMetrics.REJECTED_METRIC, "limit", limit, "scope", scope)
        .count();
  }

  @Test
  void allowsRequestWhenWithinLimit() throws Exception {
    when(queryLimiter.tryAcquire(anyString())).thenReturn(ALLOWED);

    var request = new MockHttpServletRequest("POST", "/api/v1/query");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
    assertThat(rejected("query", "client")).isZero();
  }

  @Test
  void returns429WithRetryAfterWhenQueryLimitExceeded() throws Exception {
    when(queryLimiter.tryAcquire(anyString())).thenReturn(REJECTED);

    var request = new MockHttpServletRequest("POST", "/api/v1/query");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("17");
    assertThat(response.getContentAsString())
        .contains(RateLimitFilter.RATE_LIMIT_MESSAGE)
        .contains("\"status\":429");
    assertThat(chain.getRequest()).isNull();
    assertThat(rejected("query", "client")).isEqualTo(1.0);
    assertThat(rejected("query", "global")).isZero();
  }

  @Test
  void returns429WhenIndexingLimitExceeded() throws Exception {
    when(indexingLimiter.tryAcquire(anyString())).thenReturn(REJECTED);

    var request =
        new MockHttpServletRequest("POST", "/api/v1/libraries/" + UUID.randomUUID() + "/indexing");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentAsString()).contains(RateLimitFilter.RATE_LIMIT_MESSAGE);
  }

  @Test
  void returns429WhenSourceTestLimitExceeded() throws Exception {
    when(sourceTestLimiter.tryAcquire(anyString())).thenReturn(REJECTED);

    var request = new MockHttpServletRequest("POST", "/api/v1/libraries/source-test");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentAsString()).contains(RateLimitFilter.RATE_LIMIT_MESSAGE);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void keysTheWebhookLimiterByLibraryAndReturns429WhenExceeded() throws Exception {
    // #1140: the intake is reachable without a session, so the limiter is what bounds the
    // signature checks a stranger can cause - per library, like the indexing trigger.
    UUID library = UUID.randomUUID();
    when(webhookLimiter.tryAcquire(anyString())).thenReturn(REJECTED);

    var request =
        new MockHttpServletRequest("POST", "/api/v1/libraries/" + library + "/confluence-webhook");
    request.setRemoteAddr("203.0.113.7");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(chain.getRequest()).isNull();
    verify(webhookLimiter).tryAcquire("203.0.113.7:" + library);
    verify(indexingLimiter, never()).tryAcquire(anyString());
  }

  @Test
  void theListingsShareTheSourceTestBucket() throws Exception {
    // ADR-0027: the bucket listing is the same kind of outbound probe as the connection test and
    // the space listing - one limiter, keyed by the client alone
    when(sourceTestLimiter.tryAcquire(anyString())).thenReturn(REJECTED);
    for (String path :
        List.of("/api/v1/libraries/s3/buckets", "/api/v1/libraries/confluence/spaces")) {
      var request = new MockHttpServletRequest("POST", path);
      request.setRemoteAddr("203.0.113.7");
      var response = new MockHttpServletResponse();
      var chain = new MockFilterChain();

      filter.doFilter(request, response, chain);

      assertThat(response.getStatus()).as(path).isEqualTo(429);
      assertThat(chain.getRequest()).isNull();
    }
    verify(sourceTestLimiter, org.mockito.Mockito.times(2)).tryAcquire("203.0.113.7");
  }

  @Test
  void sourceTestLimitDoesNotApplyToTheIndexingTrigger() throws Exception {
    // The literal source-test pattern must not accidentally also match
    // /api/v1/libraries/{libraryId}/indexing - a regression here would either double-limit the
    // indexing trigger or leave source-test unlimited, depending on rule order.
    when(sourceTestLimiter.tryAcquire(anyString())).thenReturn(REJECTED);
    when(indexingLimiter.tryAcquire(anyString())).thenReturn(ALLOWED);

    var request =
        new MockHttpServletRequest("POST", "/api/v1/libraries/" + UUID.randomUUID() + "/indexing");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void indexingLimitIsKeyedPerLibraryNotJustPerClientIp() throws Exception {
    // Real RateLimitService instead of a mock: the finding was that a limiter shared across
    // libraries would block a second library's trigger from the very same client. Using a real
    // instance with maxRequests=1 proves the key now includes the library id.
    var perLibraryFilter =
        filter(
            List.of(), List.of(new Rule("indexing", INDEXING, new RateLimitService(1, 60), null)));

    var libraryOne = UUID.randomUUID();
    var libraryTwo = UUID.randomUUID();

    var firstLibraryFirstRequest =
        new MockHttpServletRequest("POST", "/api/v1/libraries/" + libraryOne + "/indexing");
    var firstResponse = new MockHttpServletResponse();
    perLibraryFilter.doFilter(firstLibraryFirstRequest, firstResponse, new MockFilterChain());
    assertThat(firstResponse.getStatus()).isEqualTo(200);

    var secondLibraryFirstRequest =
        new MockHttpServletRequest("POST", "/api/v1/libraries/" + libraryTwo + "/indexing");
    var secondResponse = new MockHttpServletResponse();
    perLibraryFilter.doFilter(secondLibraryFirstRequest, secondResponse, new MockFilterChain());
    assertThat(secondResponse.getStatus()).isEqualTo(200);

    var firstLibrarySecondRequest =
        new MockHttpServletRequest("POST", "/api/v1/libraries/" + libraryOne + "/indexing");
    var thirdResponse = new MockHttpServletResponse();
    perLibraryFilter.doFilter(firstLibrarySecondRequest, thirdResponse, new MockFilterChain());
    assertThat(thirdResponse.getStatus()).isEqualTo(429);
    assertThat(thirdResponse.getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
  }

  @Test
  void indexingStatusEndpointIsNotRateLimited() throws Exception {
    // The trailing $ in the indexing rule deliberately excludes the sibling status endpoint
    // (GET .../indexing/status) - mirrors the old /trigger vs /status split.
    var request =
        new MockHttpServletRequest(
            "GET", "/api/v1/libraries/" + UUID.randomUUID() + "/indexing/status");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void passesUnlimitedEndpointsThrough() throws Exception {
    var request = new MockHttpServletRequest("GET", "/api/health");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void skipsNonApiRequestsAndCorsPreflights() throws Exception {
    for (var request :
        List.of(
            new MockHttpServletRequest("GET", "/actuator/health"),
            new MockHttpServletRequest("OPTIONS", "/api/v1/auth/local/login"))) {
      var response = new MockHttpServletResponse();
      var chain = new MockFilterChain();

      filter.doFilter(request, response, chain);

      assertThat(response.getStatus()).as(request.getRequestURI()).isEqualTo(200);
      assertThat(chain.getRequest()).isNotNull();
    }
    verify(loginLimiter, never()).tryAcquire(anyString());
  }

  @Test
  void returns429AndWarnsAndCountsWhenAGlobalLimitIsExceeded() throws Exception {
    when(loginLimiter.tryAcquire(anyString())).thenReturn(ALLOWED);
    when(globalLoginLimiter.tryAcquire(anyString())).thenReturn(Decision.reject(42));
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Logger logger = (Logger) LoggerFactory.getLogger(RateLimitFilter.class);
    logger.addAppender(appender);
    try {
      var request = new MockHttpServletRequest("POST", "/api/v1/auth/local/login");
      var response = new MockHttpServletResponse();
      var chain = new MockFilterChain();

      filter.doFilter(request, response, chain);

      assertThat(response.getStatus()).isEqualTo(429);
      assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
      assertThat(response.getContentAsString()).contains(RateLimitFilter.RATE_LIMIT_MESSAGE);
      assertThat(chain.getRequest()).isNull();
    } finally {
      logger.detachAppender(appender);
    }
    assertThat(appender.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anySatisfy(message -> assertThat(message).contains("Global").contains("local-auth-login"));
    assertThat(rejected("local-auth-login", "global")).isEqualTo(1.0);
    assertThat(rejected("local-auth-login", "client")).isZero();
    verify(loginLimiter, never()).tryAcquire(anyString());
  }

  @Test
  void aRuleWithoutAGlobalLimitOnlyChecksTheClient() throws Exception {
    var refreshLimiter = mock(RateLimitService.class);
    when(refreshLimiter.tryAcquire(anyString())).thenReturn(ALLOWED);
    var noGlobal =
        filter(
            List.of(),
            List.of(new Rule("refresh", "^/api/v1/auth/local/refresh$", refreshLimiter, null)));

    var request = new MockHttpServletRequest("POST", "/api/v1/auth/local/refresh");
    request.setRemoteAddr("203.0.113.7");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    noGlobal.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    verify(refreshLimiter).tryAcquire("203.0.113.7");
  }

  @Test
  void ignoresXForwardedForWithoutATrustedProxy() throws Exception {
    // the connection's own address is the key; the header would let a client choose its bucket
    when(loginLimiter.tryAcquire(anyString())).thenReturn(ALLOWED);

    var request = new MockHttpServletRequest("POST", "/api/v1/auth/local/login");
    request.setRemoteAddr("203.0.113.50");
    request.addHeader("X-Forwarded-For", "198.51.100.1, 70.41.3.18");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    verify(loginLimiter).tryAcquire("203.0.113.50");
  }

  @Test
  void usesXForwardedForBehindATrustedProxy() throws Exception {
    when(loginLimiter.tryAcquire(anyString())).thenReturn(ALLOWED);
    var behindProxy = filter(List.of("172.28.0.0/16"), defaultRules());

    var request = new MockHttpServletRequest("POST", "/api/v1/auth/local/login");
    request.setRemoteAddr("172.28.0.3");
    request.addHeader("X-Forwarded-For", "9.9.9.9, 198.51.100.1");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    behindProxy.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    verify(loginLimiter).tryAcquire("198.51.100.1");
  }
}
