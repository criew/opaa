package io.opaa.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.observability.RateLimitMetrics;
import io.opaa.ratelimit.RateLimitProperties.EndpointLimit;
import io.opaa.ratelimit.RateLimitProperties.LocalAuthLimit;
import io.opaa.ratelimit.RateLimitProperties.LocalAuthLimits;
import io.opaa.security.TrustedProxyClientIpResolver;
import io.opaa.test.InMemoryInstallationCounters;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * The wiring of the rules, which {@code RateLimitFilterTest} cannot see: that each switchable
 * self-service rule asks its <em>own</em> flow and a served flow is still counted, and that the
 * chat search and the search with its hits have budgets of their own. No Spring context; the
 * production {@code @Bean} method is called directly, with the shipped self-service defaults (five
 * requests per client and hour).
 */
class RateLimitConfigurationTest {

  private static final String SEARCH = "/api/v1/search";
  private static final String REGISTER = "/api/v1/auth/local/register";
  private static final String FORGOT_PASSWORD = "/api/v1/auth/local/forgot-password";
  private static final int SHIPPED_BUDGET = 5;
  private static final String CLIENT = "203.0.113.7";
  private static final String LOGIN = "/api/v1/auth/local/login";

  private boolean passwordReset;
  private boolean selfRegistration;

  @Test
  void eachSelfServiceRuleCountsOnlyWhileItsOwnFlowIsServed() throws Exception {
    Filter filter = filter();
    selfRegistration = true;

    // the served flow is counted, the switched-off one next to it is not - a swapped pair of
    // suppliers would show up right here
    assertThat(statusAfter(filter, REGISTER, SHIPPED_BUDGET + 1)).isEqualTo(429);
    assertThat(statusAfter(filter, FORGOT_PASSWORD, SHIPPED_BUDGET + 1)).isEqualTo(200);

    // switched on later, the budget is untouched: nothing was spent while it was off
    passwordReset = true;
    assertThat(statusAfter(filter, FORGOT_PASSWORD, SHIPPED_BUDGET)).isEqualTo(200);
    assertThat(statusAfter(filter, FORGOT_PASSWORD, 1)).isEqualTo(429);
  }

  @Test
  void theChatSearchCountsItsOwnBudgetApartFromTheQueryAndTheChatList() throws Exception {
    EndpointLimit wide = new EndpointLimit(1000, 60, 1000);
    EndpointLimit chatSearch = new EndpointLimit(3, 60, 1000);
    Filter filter =
        filter(
            new RateLimitProperties(
                true, List.of(), wide, wide, wide, wide, wide, chatSearch, wide, null));

    assertThat(statusAfter(filter, chatSearchIn(UUID.randomUUID()), 3)).isEqualTo(200);
    // one bucket per client across spaces: another space buys no fresh budget
    assertThat(statusAfter(filter, chatSearchIn(UUID.randomUUID()), 1)).isEqualTo(429);
    assertThat(statusAfter(filter, "/api/v1/query", 1)).isEqualTo(200);
    assertThat(statusAfter(filter, "/api/v1/spaces/" + UUID.randomUUID() + "/chats", 1))
        .isEqualTo(200);
  }

  @Test
  void theSearchAndItsHitsShareOneBudgetOfTheirOwn() throws Exception {
    EndpointLimit wide = new EndpointLimit(1000, 60, 1000);
    EndpointLimit search = new EndpointLimit(3, 60, 1000);
    Filter filter =
        filter(
            new RateLimitProperties(
                true, List.of(), wide, wide, wide, wide, wide, wide, search, null));

    assertThat(statusAfter(filter, SEARCH, 2)).isEqualTo(200);
    assertThat(statusAfter(filter, hit(UUID.randomUUID()), 1)).isEqualTo(200);
    // another hit id buys no fresh budget, and neither does going back to the search
    assertThat(statusAfter(filter, hit(UUID.randomUUID()), 1)).isEqualTo(429);
    assertThat(statusAfter(filter, SEARCH, 1)).isEqualTo(429);
    // the cheap reads next to it and the other budgets stay open
    assertThat(statusAfter(filter, "/api/v1/search/libraries", 1)).isEqualTo(200);
    assertThat(statusAfter(filter, "/api/v1/search/availability", 1)).isEqualTo(200);
    assertThat(statusAfter(filter, "/api/v1/search/metadata-filter-options", 1)).isEqualTo(200);
    assertThat(statusAfter(filter, "/api/v1/query", 1)).isEqualTo(200);
    assertThat(statusAfter(filter, chatSearchIn(UUID.randomUUID()), 1)).isEqualTo(200);
  }

  @Test
  void aRefusedSearchSaysWhenToRetryInTheOneGermanMessage() throws Exception {
    EndpointLimit wide = new EndpointLimit(1000, 60, 1000);
    EndpointLimit search = new EndpointLimit(1, 60, 1000);
    Filter filter =
        filter(
            new RateLimitProperties(
                true, List.of(), wide, wide, wide, wide, wide, wide, search, null));
    answerSignedIn(filter, SEARCH, CLIENT, 200);

    MockHttpServletResponse refused = respond(filter, SEARCH, CLIENT, 200, true);

    assertThat(refused.getStatus()).isEqualTo(429);
    assertThat(Long.parseLong(refused.getHeader("Retry-After"))).isBetween(1L, 60L);
    assertThat(refused.getContentAsString(StandardCharsets.UTF_8))
        .contains(RateLimitFilter.RATE_LIMIT_MESSAGE);
  }

  @Test
  void theSearchBudgetIsKeptPerClient() throws Exception {
    EndpointLimit wide = new EndpointLimit(1000, 60, 1000);
    EndpointLimit search = new EndpointLimit(2, 60, 1000);
    Filter filter =
        filter(
            new RateLimitProperties(
                true, List.of(), wide, wide, wide, wide, wide, wide, search, null));

    assertThat(statusAfter(filter, SEARCH, 3)).isEqualTo(429);

    assertThat(answerSignedIn(filter, SEARCH, "203.0.113.40", 200)).isEqualTo(200);
    assertThat(answerSignedIn(filter, hit(UUID.randomUUID()), "203.0.113.40", 200)).isEqualTo(200);
    assertThat(answerSignedIn(filter, SEARCH, "203.0.113.40", 200)).isEqualTo(429);
  }

  @Test
  void theSearchGlobalCeilingCountsAcrossAllClients() throws Exception {
    EndpointLimit wide = new EndpointLimit(1000, 60, 1000);
    EndpointLimit search = new EndpointLimit(1000, 60, 3);
    Filter filter =
        filter(
            new RateLimitProperties(
                true, List.of(), wide, wide, wide, wide, wide, wide, search, null));

    for (int client = 1; client <= 3; client++) {
      assertThat(answerSignedIn(filter, SEARCH, "203.0.113." + (50 + client), 200)).isEqualTo(200);
    }
    assertThat(answerSignedIn(filter, hit(UUID.randomUUID()), "203.0.113.60", 200)).isEqualTo(429);
  }

  @Test
  void requestsRefusedAsUnauthenticatedLeaveTheGlobalCeilingsBehindTheSignInUntouched()
      throws Exception {
    // regression guard: the filter runs before the security chain, so a 401 must not keep the
    // global token it took - otherwise strangers exhaust the shared budget for everyone signed in
    EndpointLimit ceilingOfTwo = new EndpointLimit(10, 60, 2);
    Filter filter =
        filter(
            new RateLimitProperties(
                true,
                List.of(),
                ceilingOfTwo,
                ceilingOfTwo,
                ceilingOfTwo,
                ceilingOfTwo,
                ceilingOfTwo,
                ceilingOfTwo,
                ceilingOfTwo,
                null));

    for (String path :
        List.of(
            "/api/v1/query",
            "/api/v1/libraries/" + UUID.randomUUID() + "/indexing",
            "/api/v1/libraries/source-test",
            "/api/v1/documents/" + UUID.randomUUID() + "/content",
            "/api/v1/libraries/" + UUID.randomUUID() + "/push",
            chatSearchIn(UUID.randomUUID()),
            SEARCH)) {
      for (int stranger = 1; stranger <= 5; stranger++) {
        assertThat(answer(filter, path, "198.51.100." + stranger, 401)).as(path).isEqualTo(401);
      }
      assertThat(answerSignedIn(filter, path, "203.0.113.20", 200)).as(path).isEqualTo(200);
      assertThat(answerSignedIn(filter, path, "203.0.113.21", 200)).as(path).isEqualTo(200);
      // the two admitted requests spend the ceiling, as before
      assertThat(answerSignedIn(filter, path, "203.0.113.22", 200)).as(path).isEqualTo(429);
    }
  }

  @Test
  void aSignInRefusedAsUnauthenticatedStillSpendsTheGlobalCeilingOfTheLogin() throws Exception {
    // ADR-0033, Entscheidung 9: the global ceiling of the login counts failed attempts - that is
    // what it warns about
    EndpointLimit wide = new EndpointLimit(1000, 60, 1000);
    LocalAuthLimits localAuth =
        new LocalAuthLimits(
            new LocalAuthLimit(10, 60, 2, null), null, null, null, null, null, null, null);
    Filter filter =
        filter(
            new RateLimitProperties(
                true, List.of(), wide, wide, wide, wide, wide, wide, wide, localAuth));

    assertThat(answer(filter, LOGIN, "198.51.100.1", 401)).isEqualTo(401);
    assertThat(answer(filter, LOGIN, "198.51.100.2", 401)).isEqualTo(401);
    assertThat(answer(filter, LOGIN, "203.0.113.20", 200)).isEqualTo(429);
  }

  @Test
  void aChangingSegmentThatIsNoLibraryIdBuysNoFreshClientBudget() throws Exception {
    EndpointLimit onePerClient = new EndpointLimit(1, 60, 1000);
    Filter filter =
        filter(
            new RateLimitProperties(
                true,
                List.of(),
                onePerClient,
                onePerClient,
                onePerClient,
                onePerClient,
                onePerClient,
                onePerClient,
                onePerClient,
                null));
    UUID library = UUID.randomUUID();

    for (String suffix : List.of("/indexing", "/push")) {
      assertThat(answerSignedIn(filter, "/api/v1/libraries/a" + suffix, CLIENT, 404))
          .isEqualTo(404);
      assertThat(answerSignedIn(filter, "/api/v1/libraries/b" + suffix, CLIENT, 404))
          .isEqualTo(429);
      // another spelling of the same id is the same library
      assertThat(
              answerSignedIn(filter, "/api/v1/libraries/" + library + suffix, "203.0.113.30", 200))
          .isEqualTo(200);
      String upperCase = library.toString().toUpperCase(Locale.ROOT);
      assertThat(
              answerSignedIn(
                  filter, "/api/v1/libraries/" + upperCase + suffix, "203.0.113.30", 200))
          .as(suffix)
          .isEqualTo(429);
    }
  }

  @Test
  void unauthenticatedPushesFromOneAddressShareOneBudgetAcrossLibraries() throws Exception {
    // the push intake checks secrets without a session: library ids a stranger makes up must not
    // multiply the checks one address can cause
    EndpointLimit wide = new EndpointLimit(1000, 60, 1000);
    EndpointLimit webhook = new EndpointLimit(3, 60, 1000);
    Filter filter =
        filter(
            new RateLimitProperties(
                true, List.of(), wide, wide, wide, wide, webhook, wide, wide, null));

    for (int attempt = 0; attempt < 3; attempt++) {
      assertThat(answer(filter, pushTo(UUID.randomUUID()), CLIENT, 401)).isEqualTo(401);
    }
    assertThat(answer(filter, pushTo(UUID.randomUUID()), CLIENT, 401)).isEqualTo(429);
  }

  @Test
  void authenticatedPushesFromOneAddressKeepABudgetPerLibrary() throws Exception {
    // one source notifying several libraries is several senders
    EndpointLimit wide = new EndpointLimit(1000, 60, 1000);
    EndpointLimit webhook = new EndpointLimit(1, 60, 1000);
    Filter filter =
        filter(
            new RateLimitProperties(
                true, List.of(), wide, wide, wide, wide, webhook, wide, wide, null));

    for (int library = 0; library < 3; library++) {
      assertThat(answerSignedIn(filter, pushTo(UUID.randomUUID()), CLIENT, 202)).isEqualTo(202);
    }
  }

  private static String pushTo(UUID libraryId) {
    return "/api/v1/libraries/" + libraryId + "/push";
  }

  /**
   * One request from {@code client} that does not authenticate; behind the filter it is answered
   * with {@code status}. Returns the status the client sees.
   */
  private static int answer(Filter filter, String path, String client, int status)
      throws Exception {
    return answer(filter, path, client, status, false);
  }

  /** As {@link #answer}, for a request the security chain or the push handler authenticated. */
  private static int answerSignedIn(Filter filter, String path, String client, int status)
      throws Exception {
    return answer(filter, path, client, status, true);
  }

  private static int answer(
      Filter filter, String path, String client, int status, boolean authenticated)
      throws Exception {
    return respond(filter, path, client, status, authenticated).getStatus();
  }

  private static MockHttpServletResponse respond(
      Filter filter, String path, String client, int status, boolean authenticated)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setRemoteAddr(client);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain =
        (req, res) -> {
          if (authenticated) {
            RateLimitAuthentication.markAuthenticatedBySession((HttpServletRequest) req);
            RateLimitAuthentication.markAuthenticatedByHandler((HttpServletRequest) req);
          }
          ((HttpServletResponse) res).setStatus(status);
        };
    filter.doFilter(request, response, chain);
    return response;
  }

  private static String hit(UUID hitId) {
    return "/api/v1/search/hits/" + hitId;
  }

  private static String chatSearchIn(UUID spaceId) {
    return "/api/v1/spaces/" + spaceId + "/chats/search";
  }

  /**
   * The status of the last of {@code count} signed-in requests from one client; earlier ones are
   * ignored.
   */
  private static int statusAfter(Filter filter, String path, int count) throws Exception {
    int status = 0;
    for (int i = 0; i < count; i++) {
      status = answerSignedIn(filter, path, CLIENT, 200);
    }
    return status;
  }

  private Filter filter() {
    EndpointLimit wide = new EndpointLimit(1000, 60, 1000);
    return filter(
        new RateLimitProperties(true, List.of(), wide, wide, wide, wide, wide, wide, wide, null));
  }

  private Filter filter(RateLimitProperties properties) {
    return new RateLimitConfiguration()
        .rateLimitFilterRegistration(
            properties,
            new TrustedProxyClientIpResolver(List.of()),
            new RateLimitMetrics(new SimpleMeterRegistry()),
            JsonMapper.builder().build(),
            availability(),
            new InMemoryInstallationCounters())
        .getFilter();
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<SelfServiceEndpointAvailability> availability() {
    SelfServiceEndpointAvailability flows =
        new SelfServiceEndpointAvailability() {

          @Override
          public boolean isPasswordResetAvailable() {
            return passwordReset;
          }

          @Override
          public boolean isSelfRegistrationAvailable() {
            return selfRegistration;
          }
        };
    ObjectProvider<SelfServiceEndpointAvailability> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable(any())).thenReturn(flows);
    return provider;
  }
}
