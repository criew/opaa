package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

  private RateLimitFilter filter;
  private RateLimitService queryLimiter;
  private RateLimitService indexingLimiter;
  private RateLimitService globalQueryLimiter;
  private RateLimitService globalIndexingLimiter;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    queryLimiter = mock(RateLimitService.class);
    indexingLimiter = mock(RateLimitService.class);
    globalQueryLimiter = mock(RateLimitService.class);
    globalIndexingLimiter = mock(RateLimitService.class);
    objectMapper = new ObjectMapper();

    Map<String, RateLimitService> perIpLimiters = new LinkedHashMap<>();
    perIpLimiters.put("/api/v1/query", queryLimiter);
    perIpLimiters.put("/api/v1/indexing/trigger", indexingLimiter);

    Map<String, RateLimitService> globalLimiters = new LinkedHashMap<>();
    globalLimiters.put("/api/v1/query", globalQueryLimiter);
    globalLimiters.put("/api/v1/indexing/trigger", globalIndexingLimiter);

    when(globalQueryLimiter.isAllowed(anyString())).thenReturn(true);
    when(globalIndexingLimiter.isAllowed(anyString())).thenReturn(true);

    filter = new RateLimitFilter(perIpLimiters, globalLimiters, objectMapper);
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

    var request = new MockHttpServletRequest("POST", "/api/v1/indexing/trigger");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentAsString()).contains("Rate limit exceeded");
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
