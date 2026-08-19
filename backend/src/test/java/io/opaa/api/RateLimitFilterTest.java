package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class RateLimitFilterTest {

  private RateLimitFilter filter;
  private RateLimitService queryLimiter;
  private RateLimitService indexingLimiter;
  private RateLimitService sourceTestLimiter;
  private RateLimitService globalQueryLimiter;
  private RateLimitService globalIndexingLimiter;
  private RateLimitService globalSourceTestLimiter;
  private JsonMapper jsonMapper;

  @BeforeEach
  void setUp() {
    queryLimiter = mock(RateLimitService.class);
    indexingLimiter = mock(RateLimitService.class);
    sourceTestLimiter = mock(RateLimitService.class);
    globalQueryLimiter = mock(RateLimitService.class);
    globalIndexingLimiter = mock(RateLimitService.class);
    globalSourceTestLimiter = mock(RateLimitService.class);
    jsonMapper = JsonMapper.builder().build();

    Map<String, RateLimitService> perIpLimiters = new LinkedHashMap<>();
    perIpLimiters.put("^/api/v1/query", queryLimiter);
    perIpLimiters.put("^/api/v1/libraries/([^/]+)/indexing$", indexingLimiter);
    // #514/PR #537 review, finding 3: mirrors RateLimitConfiguration's own registration of
    // POST /api/v1/libraries/source-test.
    perIpLimiters.put("^/api/v1/libraries/source-test$", sourceTestLimiter);

    Map<String, RateLimitService> globalLimiters = new LinkedHashMap<>();
    globalLimiters.put("^/api/v1/query", globalQueryLimiter);
    globalLimiters.put("^/api/v1/libraries/([^/]+)/indexing$", globalIndexingLimiter);
    globalLimiters.put("^/api/v1/libraries/source-test$", globalSourceTestLimiter);

    when(globalQueryLimiter.isAllowed(anyString())).thenReturn(true);
    when(globalIndexingLimiter.isAllowed(anyString())).thenReturn(true);
    when(globalSourceTestLimiter.isAllowed(anyString())).thenReturn(true);

    filter = new RateLimitFilter(perIpLimiters, globalLimiters, jsonMapper);
  }

  @Test
  void allowsRequestWhenWithinLimit() throws Exception {
    when(queryLimiter.isAllowed(anyString())).thenReturn(true);

    var request = new MockHttpServletRequest("POST", "/api/v1/query");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void returns429WhenQueryLimitExceeded() throws Exception {
    when(queryLimiter.isAllowed(anyString())).thenReturn(false);

    var request = new MockHttpServletRequest("POST", "/api/v1/query");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getContentAsString()).contains("Rate limit exceeded");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void returns429WhenIndexingLimitExceeded() throws Exception {
    when(indexingLimiter.isAllowed(anyString())).thenReturn(false);

    var request =
        new MockHttpServletRequest(
            "POST", "/api/v1/libraries/" + java.util.UUID.randomUUID() + "/indexing");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentAsString()).contains("Rate limit exceeded");
  }

  @Test
  void returns429WhenSourceTestLimitExceeded() throws Exception {
    when(sourceTestLimiter.isAllowed(anyString())).thenReturn(false);

    var request = new MockHttpServletRequest("POST", "/api/v1/libraries/source-test");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentAsString()).contains("Rate limit exceeded");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void sourceTestLimitDoesNotApplyToTheIndexingTrigger() throws Exception {
    // The literal source-test pattern must not accidentally also match
    // /api/v1/libraries/{libraryId}/indexing - a regression here would either double-limit the
    // indexing trigger or leave source-test unlimited, depending on map iteration order.
    when(sourceTestLimiter.isAllowed(anyString())).thenReturn(false);
    when(indexingLimiter.isAllowed(anyString())).thenReturn(true);

    var request =
        new MockHttpServletRequest(
            "POST", "/api/v1/libraries/" + java.util.UUID.randomUUID() + "/indexing");
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
    var realIndexingLimiter = new RateLimitService(1, 60);
    Map<String, RateLimitService> perIpLimiters = new LinkedHashMap<>();
    perIpLimiters.put("^/api/v1/libraries/([^/]+)/indexing$", realIndexingLimiter);
    Map<String, RateLimitService> globalLimiters = new LinkedHashMap<>();
    globalLimiters.put("^/api/v1/libraries/([^/]+)/indexing$", globalIndexingLimiter);
    var perLibraryFilter = new RateLimitFilter(perIpLimiters, globalLimiters, jsonMapper);

    var libraryOne = java.util.UUID.randomUUID();
    var libraryTwo = java.util.UUID.randomUUID();

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
  }

  @Test
  void indexingStatusEndpointIsNotRateLimited() throws Exception {
    // The trailing $ in the indexing rule deliberately excludes the sibling status endpoint
    // (GET .../indexing/status) - mirrors the old /trigger vs /status split.
    var request =
        new MockHttpServletRequest(
            "GET", "/api/v1/libraries/" + java.util.UUID.randomUUID() + "/indexing/status");
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
  void skipsNonApiRequests() throws Exception {
    var request = new MockHttpServletRequest("GET", "/actuator/health");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void returns429WhenGlobalQueryLimitExceeded() throws Exception {
    when(queryLimiter.isAllowed(anyString())).thenReturn(true);
    when(globalQueryLimiter.isAllowed(anyString())).thenReturn(false);

    var request = new MockHttpServletRequest("POST", "/api/v1/query");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentAsString()).contains("Rate limit exceeded");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void usesXForwardedForHeader() throws Exception {
    when(queryLimiter.isAllowed("203.0.113.50")).thenReturn(true);
    when(queryLimiter.isAllowed("127.0.0.1")).thenReturn(false);

    var request = new MockHttpServletRequest("POST", "/api/v1/query");
    request.addHeader("X-Forwarded-For", "203.0.113.50, 70.41.3.18");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
  }
}
