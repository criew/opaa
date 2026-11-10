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
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/**
 * The load bounds as shipped in {@code application.yml}, bound the way Spring binds them, in front
 * of the production filter and {@link AuthenticatedRequestMarkerFilter}: every global ceiling has a
 * budget per account below it, an account the operation turns away cannot spend a ceiling for
 * everyone, every writing operation of the specification falls under a rule, and opening a live
 * dictation is limited. No Spring context.
 */
class RateLimitShippedDefaultsTest {

  /**
   * Ceilings without an account below them, each for a reason: the push intake authenticates by a
   * library's secret, not an account, and keeps a budget per library instead; the sign-in ceilings
   * count before any account exists and count failed attempts on purpose (ADR-0033, 9).
   */
  private static final Set<String> CEILINGS_WITHOUT_AN_ACCOUNT = Set.of("webhook");

  private static final Set<String> WRITE_METHODS = Set.of("post", "put", "patch", "delete");

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void everyShippedGlobalCeilingHasABudgetPerAccountBelowIt() throws Exception {
    RateLimitProperties shipped = shipped(Map.of());
    List<String> withoutAccount = new ArrayList<>();
    int checked = 0;

    for (RecordComponent component : RateLimitProperties.class.getRecordComponents()) {
      if (component.getType() != EndpointLimit.class
          || CEILINGS_WITHOUT_AN_ACCOUNT.contains(component.getName())) {
        continue;
      }
      EndpointLimit limit = (EndpointLimit) component.getAccessor().invoke(shipped);
      checked++;
      if (!limit.hasAccountLimit() || limit.maxRequestsPerAccount() >= limit.globalMaxRequests()) {
        withoutAccount.add(component.getName());
      }
    }

    assertThat(checked).as("the limits of the properties were found").isGreaterThan(5);
    assertThat(withoutAccount)
        .as(
            "a global ceiling without a smaller budget per account lets one account spend it for"
                + " the whole installation")
        .isEmpty();
  }

  /**
   * An account without rights at a library, rotating addresses and library ids: it is stopped by
   * its own budget, and its refused calls leave the installation's ceiling to everyone else.
   */
  @Test
  void anAccountTheOperationTurnsAwayCannotSpendACeilingForEveryone() throws Exception {
    Filter filter = filter(shipped(Map.of()));
    Map<String, String> paths =
        Map.of(
            "POST", "/api/v1/libraries/%s/indexing",
            "GET", "/api/v1/documents/%s/content");
    for (Map.Entry<String, String> path : paths.entrySet()) {
      Authentication mallory = account("mallory-" + path.getValue());
      int refused = 0;
      for (int attempt = 0; attempt < 200; attempt++) {
        int status =
            answer(
                filter,
                path.getKey(),
                path.getValue().formatted(UUID.randomUUID()),
                "198.51.100." + (attempt % 250 + 1),
                mallory,
                403);
        if (status == 429) {
          refused++;
        }
      }
      assertThat(refused).as("%s is refused past its account budget", path).isPositive();

      for (int other = 1; other <= 2; other++) {
        assertThat(
                answer(
                    filter,
                    path.getKey(),
                    path.getValue().formatted(UUID.randomUUID()),
                    "203.0.113." + other,
                    account("colleague-" + other + path.getValue()),
                    200))
            .as("%s for another account", path)
            .isEqualTo(200);
      }
    }
  }

  @Test
  void theSourceTestOfAnAccountTheOperationTurnsAwayLeavesTheCeilingToOthers() throws Exception {
    Filter filter = filter(shipped(Map.of()));
    String path = "/api/v1/libraries/source-test";
    for (int attempt = 0; attempt < 100; attempt++) {
      answer(filter, "POST", path, "198.51.100." + (attempt + 1), account("mallory"), 404);
    }

    assertThat(answer(filter, "POST", path, "203.0.113.9", account("colleague"), 200))
        .isEqualTo(200);
  }

  @Test
  void everyWritingOperationOfTheSpecificationFallsUnderARule() throws Exception {
    Map<String, Object> budgetOfOne = new HashMap<>();
    for (String name : shippedPropertyNames()) {
      if (name.startsWith("opaa.rate-limit.") && name.endsWith(".max-requests")) {
        budgetOfOne.put(name, "1");
      }
    }
    RateLimitProperties properties = shipped(budgetOfOne);
    List<String> uncovered = new ArrayList<>();
    int writes = 0;

    for (Map.Entry<String, Map<String, Object>> path : specificationPaths().entrySet()) {
      for (String method : path.getValue().keySet()) {
        if (!WRITE_METHODS.contains(method)) {
          continue;
        }
        writes++;
        String concrete = path.getKey().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
        Filter filter = filter(properties);
        String upper = method.toUpperCase(Locale.ROOT);
        answer(filter, upper, concrete, "203.0.113.50", account("anna"), 200);
        if (answer(filter, upper, concrete, "203.0.113.50", account("anna"), 200) != 429) {
          uncovered.add(upper + " " + path.getKey());
        }
      }
    }

    assertThat(writes).as("the specification's writing operations were found").isGreaterThan(100);
    assertThat(uncovered).as("writing operations no rate-limit rule covers").isEmpty();
  }

  @Test
  void openingALiveDictationIsLimitedPerAccount() throws Exception {
    Filter filter = filter(shipped(Map.of()));
    String stream = "/api/v1/transcriptions/stream";
    Authentication anna = account("anna");
    int lastStatus = 0;

    for (int attempt = 0; attempt < 50; attempt++) {
      lastStatus = answer(filter, "GET", stream, "198.51.100." + (attempt + 1), anna, 200);
    }

    assertThat(lastStatus).isEqualTo(429);
    assertThat(answer(filter, "GET", stream, "203.0.113.9", account("bert"), 200)).isEqualTo(200);
  }

  /** The shipped {@code opaa.rate-limit} block, placeholders at their defaults, with overrides. */
  private static RateLimitProperties shipped(Map<String, Object> overrides) throws IOException {
    MutablePropertySources sources = new MutablePropertySources();
    sources.addFirst(new MapPropertySource("overrides", overrides));
    sources.addLast(firstDocument());
    return new Binder(
            ConfigurationPropertySources.from(sources),
            new PropertySourcesPlaceholdersResolver(sources))
        .bind("opaa.rate-limit", RateLimitProperties.class)
        .get();
  }

  private static PropertySource<?> firstDocument() throws IOException {
    return new YamlPropertySourceLoader()
        .load("application.yml", new ClassPathResource("application.yml"))
        .getFirst();
  }

  private static String[] shippedPropertyNames() throws IOException {
    return ((EnumerablePropertySource<?>) firstDocument()).getPropertyNames();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, Object>> specificationPaths() throws IOException {
    try (InputStream in =
        RateLimitShippedDefaultsTest.class.getResourceAsStream("/openapi/opaa-api.yaml")) {
      Map<String, Object> spec = new Yaml().load(in);
      return (Map<String, Map<String, Object>>) spec.get("paths");
    }
  }

  /**
   * One request, authenticated as {@code authentication} by the end of the security chain; the
   * operation answers {@code status}. Returns the status the client sees.
   */
  private static int answer(
      Filter filter,
      String method,
      String path,
      String client,
      Authentication authentication,
      int status)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setRemoteAddr(client);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain securityChainEnd =
        (req, res) -> {
          SecurityContextHolder.getContext().setAuthentication(authentication);
          try {
            new AuthenticatedRequestMarkerFilter()
                .doFilter(req, res, (r, s) -> ((HttpServletResponse) s).setStatus(status));
          } finally {
            SecurityContextHolder.clearContext();
          }
        };
    filter.doFilter(request, response, securityChainEnd);
    return response.getStatus();
  }

  private static Authentication account(String name) {
    return UsernamePasswordAuthenticationToken.authenticated(name, null, List.of());
  }

  private static Filter filter(RateLimitProperties properties) {
    return new RateLimitConfiguration()
        .rateLimitFilterRegistration(
            properties,
            new TrustedProxyClientIpResolver(List.of()),
            new RateLimitMetrics(new SimpleMeterRegistry()),
            JsonMapper.builder().build(),
            allSelfServiceServed(),
            new InMemoryInstallationCounters(),
            java.time.Clock.systemUTC())
        .getFilter();
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<SelfServiceEndpointAvailability> allSelfServiceServed() {
    SelfServiceEndpointAvailability served =
        new SelfServiceEndpointAvailability() {

          @Override
          public boolean isPasswordResetAvailable() {
            return true;
          }

          @Override
          public boolean isSelfRegistrationAvailable() {
            return true;
          }
        };
    ObjectProvider<SelfServiceEndpointAvailability> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable(any())).thenReturn(served);
    return provider;
  }
}
