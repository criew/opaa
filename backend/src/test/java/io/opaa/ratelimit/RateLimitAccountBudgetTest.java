package io.opaa.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.observability.RateLimitMetrics;
import io.opaa.ratelimit.RateLimitProperties.EndpointLimit;
import io.opaa.security.TrustedProxyClientIpResolver;
import io.opaa.test.InMemoryInstallationCounters;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * The budget per account or access token of the rules behind the sign-in, and what an access token
 * owes the budget of its address. No Spring context: the production filter of {@link
 * RateLimitConfiguration} in front of the production {@link AuthenticatedRequestMarkerFilter},
 * which stands in for the end of the security chain with the authentication each test names.
 */
class RateLimitAccountBudgetTest {

  private static final String SEARCH = "/api/v1/search";
  private static final String QUERY = "/api/v1/query";
  private static final String TRANSCRIPTION = "/api/v1/transcriptions";
  private static final EndpointLimit WIDE = new EndpointLimit(1000, 60, 1000, 0);

  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void anAccountRotatingItsAddressesIsRefusedPastItsAccountBudget() throws Exception {
    Filter filter = filterWithSearch(new EndpointLimit(1000, 60, 1000, 3));
    Authentication anna = account("anna");

    for (int address = 1; address <= 3; address++) {
      assertThat(answer(filter, SEARCH, "203.0.113." + address, anna).getStatus()).isEqualTo(200);
    }
    MockHttpServletResponse refused = answer(filter, SEARCH, "203.0.113.4", anna);

    assertThat(refused.getStatus()).isEqualTo(429);
    assertThat(Long.parseLong(refused.getHeader("Retry-After"))).isBetween(1L, 60L);
    assertThat(refused.getContentAsString(StandardCharsets.UTF_8))
        .contains(RateLimitFilter.RATE_LIMIT_MESSAGE);
    assertThat(rejected("search", "account")).isEqualTo(1.0);
  }

  @Test
  void anotherAccountKeepsItsOwnBudget() throws Exception {
    Filter filter = filterWithSearch(new EndpointLimit(1000, 60, 1000, 1));
    answer(filter, SEARCH, "203.0.113.1", account("anna"));
    assertThat(answer(filter, SEARCH, "203.0.113.1", account("anna")).getStatus()).isEqualTo(429);

    assertThat(answer(filter, SEARCH, "203.0.113.1", account("bert")).getStatus()).isEqualTo(200);
  }

  @Test
  void theSameSubjectOfAnotherIssuerIsAnotherAccount() throws Exception {
    Filter filter = filterWithSearch(new EndpointLimit(1000, 60, 1000, 1));
    answer(filter, SEARCH, "203.0.113.1", jwt("https://idp.example", "4711"));

    assertThat(
            answer(filter, SEARCH, "203.0.113.1", jwt("https://idp.example", "4711")).getStatus())
        .isEqualTo(429);
    assertThat(answer(filter, SEARCH, "203.0.113.1", jwt("opaa-local", "4711")).getStatus())
        .isEqualTo(200);
  }

  @Test
  void aRefusedAccountGivesBackTheTokensOfItsAddressAndOfTheGlobalCeiling() throws Exception {
    Filter filter = filterWithSearch(new EndpointLimit(3, 60, 3, 1));
    String sharedAddress = "203.0.113.10";
    answer(filter, SEARCH, sharedAddress, account("anna"));
    for (int attempt = 0; attempt < 5; attempt++) {
      assertThat(answer(filter, SEARCH, sharedAddress, account("anna")).getStatus()).isEqualTo(429);
    }

    // the address and the ceiling still hold two requests each for the others behind it
    assertThat(answer(filter, SEARCH, sharedAddress, account("bert")).getStatus()).isEqualTo(200);
    assertThat(answer(filter, SEARCH, sharedAddress, account("carla")).getStatus()).isEqualTo(200);
    assertThat(answer(filter, SEARCH, sharedAddress, account("dora")).getStatus()).isEqualTo(429);
  }

  @Test
  void anAccessTokenIsCountedApartFromItsAccount() throws Exception {
    Filter filter = filterWithSearch(new EndpointLimit(1000, 60, 1000, 1));
    UUID person = UUID.randomUUID();
    answer(filter, SEARCH, "203.0.113.20", account(person.toString()));
    assertThat(answer(filter, SEARCH, "203.0.113.20", account(person.toString())).getStatus())
        .isEqualTo(429);

    Authentication token = accessToken(person, UUID.randomUUID());
    assertThat(answer(filter, SEARCH, "203.0.113.20", token).getStatus()).isEqualTo(200);
    assertThat(answer(filter, SEARCH, "203.0.113.20", token).getStatus()).isEqualTo(429);
    assertThat(rejected("search", "token")).isEqualTo(1.0);
  }

  @Test
  void allTokensOfOnePersonShareOneBudget() throws Exception {
    Filter filter = filterWithSearch(new EndpointLimit(1000, 60, 1000, 2));
    UUID person = UUID.randomUUID();

    assertThat(
            answer(filter, SEARCH, "203.0.113.21", accessToken(person, UUID.randomUUID()))
                .getStatus())
        .isEqualTo(200);
    assertThat(
            answer(filter, SEARCH, "203.0.113.22", accessToken(person, UUID.randomUUID()))
                .getStatus())
        .isEqualTo(200);
    // a third token of the same person buys nothing
    assertThat(
            answer(filter, SEARCH, "203.0.113.23", accessToken(person, UUID.randomUUID()))
                .getStatus())
        .isEqualTo(429);
    assertThat(rejected("search", "token")).isEqualTo(1.0);
    // another person's token is not affected
    assertThat(
            answer(
                    filter,
                    SEARCH,
                    "203.0.113.23",
                    accessToken(UUID.randomUUID(), UUID.randomUUID()))
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  void aTokenRefusedByThePersonBudgetGivesBackItsOwnToken() throws Exception {
    Filter filter = filterWithSearch(new EndpointLimit(1000, 60, 1000, 1));
    UUID person = UUID.randomUUID();
    Authentication spent = accessToken(person, UUID.randomUUID());
    Authentication second = accessToken(person, UUID.randomUUID());
    answer(filter, SEARCH, "203.0.113.24", spent);
    for (int attempt = 0; attempt < 3; attempt++) {
      assertThat(answer(filter, SEARCH, "203.0.113.24", second).getStatus()).isEqualTo(429);
    }

    // refused by the person's budget only: the token's own budget of one was given back each time
    assertThat(rejected("search", "token")).isEqualTo(3.0);
  }

  @Test
  void aRequestALaterLimitRefusesGivesBackItsGlobalToken() throws Exception {
    // the hour quota of an access token refuses in the service, behind the filter
    Filter filter = filterWithSearch(new EndpointLimit(1000, 60, 2, 0));
    Authentication exhausted = accessToken(UUID.randomUUID(), UUID.randomUUID());
    for (int attempt = 0; attempt < 4; attempt++) {
      assertThat(refusedLater(filter, SEARCH, "203.0.113.25", exhausted).getStatus())
          .isEqualTo(429);
    }

    assertThat(answer(filter, SEARCH, "203.0.113.26", account("anna")).getStatus()).isEqualTo(200);
    assertThat(answer(filter, SEARCH, "203.0.113.27", account("bert")).getStatus()).isEqualTo(200);
    assertThat(answer(filter, SEARCH, "203.0.113.28", account("carla")).getStatus()).isEqualTo(429);
  }

  @Test
  void issuerAndSubjectAreKeyedWithoutAmbiguity() throws Exception {
    Filter filter = filterWithSearch(new EndpointLimit(1000, 60, 1000, 1));
    answer(filter, SEARCH, "203.0.113.50", jwt("https://idp.example|x", "y"));
    answer(filter, SEARCH, "203.0.113.50", jwt(null, "z"));

    assertThat(
            answer(filter, SEARCH, "203.0.113.50", jwt("https://idp.example", "x|y")).getStatus())
        .isEqualTo(200);
    assertThat(answer(filter, SEARCH, "203.0.113.50", jwt("null", "z")).getStatus()).isEqualTo(200);
  }

  @Test
  void accessTokenRequestsSpendNoBudgetOfTheirAddressButTheGlobalCeiling() throws Exception {
    Filter filter = filterWithSearch(new EndpointLimit(1, 60, 4, 0));
    String sharedAddress = "203.0.113.30";
    for (int request = 0; request < 3; request++) {
      Authentication token = accessToken(UUID.randomUUID(), UUID.randomUUID());
      assertThat(answer(filter, SEARCH, sharedAddress, token).getStatus()).isEqualTo(200);
    }

    // the address is untouched for the person behind it ...
    assertThat(answer(filter, SEARCH, sharedAddress, account("anna")).getStatus()).isEqualTo(200);
    // ... while every one of these requests took its share of the ceiling
    assertThat(answer(filter, SEARCH, "203.0.113.31", account("bert")).getStatus()).isEqualTo(429);
  }

  @Test
  void theQueryHasABudgetPerAccountOfItsOwn() throws Exception {
    EndpointLimit query = new EndpointLimit(1000, 60, 1000, 2);
    Filter filter =
        filter(
            new RateLimitProperties(
                true, List.of(), query, WIDE, WIDE, WIDE, WIDE, WIDE, WIDE, null));
    Authentication anna = account("anna");

    assertThat(answer(filter, QUERY, "203.0.113.40", anna).getStatus()).isEqualTo(200);
    assertThat(answer(filter, QUERY, "203.0.113.41", anna).getStatus()).isEqualTo(200);
    assertThat(answer(filter, QUERY, "203.0.113.42", anna).getStatus()).isEqualTo(429);
    // the search of the same account draws on a budget of its own
    assertThat(answer(filter, SEARCH, "203.0.113.42", anna).getStatus()).isEqualTo(200);
  }

  @Test
  void theTranscriptionAndTheUploadHaveBudgetsPerAccountOfTheirOwn() throws Exception {
    EndpointLimit perAccountTwo = new EndpointLimit(1000, 60, 1000, 2);
    Filter filter =
        filter(
            new RateLimitProperties(
                true,
                List.of(),
                WIDE,
                WIDE,
                WIDE,
                WIDE,
                WIDE,
                WIDE,
                WIDE,
                perAccountTwo,
                perAccountTwo,
                null));
    Authentication anna = account("anna");
    String upload = "/api/v1/libraries/" + UUID.randomUUID() + "/documents";

    for (String path : List.of(TRANSCRIPTION, upload)) {
      assertThat(answer(filter, path, "203.0.113.60", anna).getStatus()).as(path).isEqualTo(200);
      assertThat(answer(filter, path, "203.0.113.61", anna).getStatus()).as(path).isEqualTo(200);
      // another address buys the same account nothing
      assertThat(answer(filter, path, "203.0.113.62", anna).getStatus()).as(path).isEqualTo(429);
      assertThat(answer(filter, path, "203.0.113.62", account("bert")).getStatus())
          .as(path)
          .isEqualTo(200);
    }
    assertThat(rejected("transcription", "account")).isEqualTo(1.0);
    assertThat(rejected("document-upload", "account")).isEqualTo(1.0);
    // the query of the same account is not touched
    assertThat(answer(filter, QUERY, "203.0.113.62", anna).getStatus()).isEqualTo(200);
  }

  @Test
  void aRuleWithoutABudgetPerAccountCountsByAddressOnly() throws Exception {
    Filter filter = filterWithSearch(new EndpointLimit(1000, 60, 1000, 0));
    Authentication anna = account("anna");

    for (int address = 1; address <= 20; address++) {
      assertThat(answer(filter, SEARCH, "198.51.100." + address, anna).getStatus()).isEqualTo(200);
    }
  }

  @Test
  void anUnauthenticatedRequestIsNotCountedForAnyAccount() throws Exception {
    Filter filter = filterWithSearch(new EndpointLimit(1000, 60, 1000, 1));

    for (int attempt = 0; attempt < 3; attempt++) {
      assertThat(answer(filter, SEARCH, "198.51.100.50", null).getStatus()).isEqualTo(200);
    }
    assertThat(rejected("search", "account")).isZero();
  }

  private Filter filterWithSearch(EndpointLimit search) {
    return filter(
        new RateLimitProperties(true, List.of(), WIDE, WIDE, WIDE, WIDE, WIDE, WIDE, search, null));
  }

  /**
   * One request from {@code client}, authenticated as {@code authentication} by the time it leaves
   * the security chain ({@code null}: not authenticated); its handler answers {@code 200}.
   */
  private static MockHttpServletResponse answer(
      Filter filter, String path, String client, Authentication authentication) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setRemoteAddr(client);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain securityChainEnd =
        (req, res) -> {
          SecurityContextHolder.getContext().setAuthentication(authentication);
          try {
            new AuthenticatedRequestMarkerFilter()
                .doFilter(
                    req,
                    res,
                    (r, s) -> ((HttpServletResponse) s).setStatus(HttpServletResponse.SC_OK));
          } finally {
            SecurityContextHolder.clearContext();
          }
        };
    filter.doFilter(request, response, securityChainEnd);
    return response;
  }

  /**
   * As {@link #answer}, for a request a limit behind the filter refuses: its handler marks it and
   * answers {@code 429}, the way {@code GlobalExceptionHandler} does.
   */
  private static MockHttpServletResponse refusedLater(
      Filter filter, String path, String client, Authentication authentication) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setRemoteAddr(client);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain securityChainEnd =
        (req, res) -> {
          SecurityContextHolder.getContext().setAuthentication(authentication);
          try {
            new AuthenticatedRequestMarkerFilter()
                .doFilter(
                    req,
                    res,
                    (r, s) -> {
                      RateLimitAuthentication.markRefusedByLaterLimit((HttpServletRequest) r);
                      ((HttpServletResponse) s).setStatus(429);
                    });
          } finally {
            SecurityContextHolder.clearContext();
          }
        };
    filter.doFilter(request, response, securityChainEnd);
    return response;
  }

  private double rejected(String limit, String scope) {
    var counter =
        meterRegistry
            .find(RateLimitMetrics.REJECTED_METRIC)
            .tag("limit", limit)
            .tag("scope", scope)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private static Authentication account(String name) {
    return UsernamePasswordAuthenticationToken.authenticated(name, null, List.of());
  }

  private static Authentication jwt(String issuer, String subject) {
    Jwt.Builder token = Jwt.withTokenValue("t").header("alg", "none").subject(subject);
    if (issuer != null) {
      token.claim("iss", issuer);
    }
    return new JwtAuthenticationToken(token.build(), List.of());
  }

  private static Authentication accessToken(UUID person, UUID token) {
    return new AccessToken(person, token);
  }

  /** Shaped like the authentication of the external-access channel: the person as principal. */
  private static final class AccessToken extends AbstractAuthenticationToken
      implements OwnQuotaAuthentication {

    private final UUID person;
    private final UUID token;

    AccessToken(UUID person, UUID token) {
      super(List.of());
      this.person = person;
      this.token = token;
      setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
      return "";
    }

    @Override
    public Object getPrincipal() {
      return person;
    }

    @Override
    public String quotaKey() {
      return token.toString();
    }

    @Override
    public String ownerKey() {
      return person.toString();
    }
  }

  private Filter filter(RateLimitProperties properties) {
    return new RateLimitConfiguration()
        .rateLimitFilterRegistration(
            properties,
            new TrustedProxyClientIpResolver(List.of()),
            new RateLimitMetrics(meterRegistry),
            JsonMapper.builder().build(),
            noSelfService(),
            new InMemoryInstallationCounters(),
            java.time.Clock.systemUTC())
        .getFilter();
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<SelfServiceEndpointAvailability> noSelfService() {
    ObjectProvider<SelfServiceEndpointAvailability> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable(any())).thenReturn(SelfServiceEndpointAvailability.NONE);
    return provider;
  }
}
