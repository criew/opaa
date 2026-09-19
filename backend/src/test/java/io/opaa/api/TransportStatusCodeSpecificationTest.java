package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.RateLimitProperties.EndpointLimit;
import io.opaa.api.RateLimitProperties.LocalAuthLimit;
import io.opaa.api.RateLimitProperties.LocalAuthLimits;
import io.opaa.auth.local.LocalSelfServiceAvailability;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.observability.RateLimitMetrics;
import io.opaa.security.TrustedProxyClientIpResolver;
import jakarta.servlet.Filter;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/**
 * The status-code rule the specification states once in {@code info.description}: an operation
 * declares what it decides itself, and what the infrastructure decides the same way for every
 * operation is described centrally instead of repeated per operation. This test is what keeps the
 * rule from eroding one endpoint at a time.
 *
 * <p>The {@code 429} half derives its expectation from the production wiring rather than from a
 * list: every path of the specification is run through the very {@link RateLimitFilter} {@link
 * RateLimitConfiguration} builds, so a new rate-limit rule without the matching declaration fails
 * here. The {@code 413} half derives the multipart uploads from the one configured bound they
 * share, and the two event intakes from the one {@code readBounded} they share - both premises are
 * read off the production side rather than listed. No Spring context.
 */
class TransportStatusCodeSpecificationTest {

  /**
   * Answered identically for every operation, therefore declared at none: the method, media-type
   * and {@code Accept} refusals of the dispatcher, and the catch-all internal error.
   */
  private static final List<String> CROSS_CUTTING = List.of("405", "406", "415", "500");

  /**
   * The only operations that decide a {@code 401} themselves rather than inheriting the resource
   * server's: the sign-in (wrong credentials), the refresh (unknown, expired or replayed cookie),
   * the handover (an unusable provider token) and the two webhook intakes (a wrong signature on a
   * call that carries no session at all).
   */
  private static final Set<String> OWN_401 =
      Set.of(
          "localLogin",
          "localRefresh",
          "localHandoverRedeem",
          "receiveConfluenceWebhook",
          "receiveS3Events");

  /**
   * Budgets that are not keyed by request path, so {@link RateLimitFilter} cannot reveal them: the
   * three external-access read paths are bounded per access token by {@code ExternalAccessQuota},
   * and the password change per authenticated account by {@code LocalAuthRateLimiter}.
   */
  private static final Set<String> LIMITED_WITHOUT_A_PATH_RULE =
      Set.of("searchKnowledge", "listSearchableLibraries", "fetchSearchHit", "localChangePassword");

  private static final Set<String> HTTP_METHODS =
      Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

  private static final String CLIENT = "203.0.113.9";
  private static final String PATH_VARIABLE = "11111111-1111-1111-1111-111111111111";

  private static Map<String, Object> spec;

  @BeforeAll
  static void loadSpec() throws Exception {
    try (InputStream in =
        TransportStatusCodeSpecificationTest.class.getResourceAsStream("/openapi/opaa-api.yaml")) {
      spec = new Yaml().load(in);
    }
  }

  @Test
  void noOperationDeclaresAStatusTheInfrastructureDecidesForAllOfThem() {
    for (String status : CROSS_CUTTING) {
      assertThat(operationsDeclaring(status)).as("operations declaring %s", status).isEmpty();
    }
  }

  @Test
  void theCentralDescriptionNamesEveryStatusTheOperationsNoLongerDeclare() {
    String description = (String) map(spec, "info").get("description");

    assertThat(description).contains("401").contains("403").contains("404");
    for (String status : CROSS_CUTTING) {
      assertThat(description).as("info.description covers %s", status).contains(status);
    }
  }

  /**
   * The multipart half of the upload limit, which is mechanical: {@code
   * spring.servlet.multipart.max-file-size} bounds every multipart request alike, so an operation
   * that takes one can always answer {@code 413}.
   */
  @Test
  void everyMultipartUploadDeclaresItsSizeLimit() {
    forEachOperation(
        (path, method, operation) -> {
          if (!consumesMultipart(operation)) {
            return;
          }
          assertThat(map(operation, "responses"))
              .as("%s takes multipart and must declare 413", operation.get("operationId"))
              .containsKey("413");
        });
  }

  /**
   * A raw-body bound is nowhere visible in the specification, so the premise of the two intakes'
   * {@code 413} is read off the production code instead of asserted: both read their body through
   * the one {@link ConfluenceWebhookController#readBounded}, and as long as that refuses an
   * oversized one, both must declare {@code 413}. Taking the declaration away from both at once -
   * the likely shape of a future change, since they share the method literally - fails here.
   */
  @Test
  void bothEventIntakesDeclareTheOneSizeLimitTheyShare() {
    MockHttpServletRequest oversized = new MockHttpServletRequest("POST", "/api/v1/libraries/x");
    oversized.setContent(new byte[ConfluenceWebhookController.MAX_BODY_BYTES + 1]);

    assertThatThrownBy(() -> ConfluenceWebhookController.readBounded(oversized))
        .as("the shared bound no longer refuses an oversized body")
        .isInstanceOf(PayloadTooLargeException.class);

    assertThat(declaredStatusesOf("receiveConfluenceWebhook")).contains("413");
    assertThat(declaredStatusesOf("receiveS3Events")).contains("413");
  }

  /**
   * Beyond the shared bound above, the two intakes also share one rate-limit rule and one
   * authentication posture, so a status one of them can answer the other can answer too.
   */
  @Test
  void theTwoEventIntakesDeclareTheSameStatuses() {
    assertThat(declaredStatusesOf("receiveConfluenceWebhook"))
        .isEqualTo(declaredStatusesOf("receiveS3Events"));
  }

  @Test
  void a401IsDeclaredOnlyWhereTheOperationItselfRefusesACredential() {
    assertThat(operationsDeclaring("401")).isEqualTo(new TreeSet<>(OWN_401));
  }

  @Test
  void a429IsDeclaredAtExactlyTheOperationsWithABudgetOfTheirOwn() throws Exception {
    Set<String> withABudget = new TreeSet<>(LIMITED_WITHOUT_A_PATH_RULE);
    forEachOperation(
        (path, method, operation) -> {
          if (isRateLimited(path)) {
            withABudget.add((String) operation.get("operationId"));
          }
        });

    assertThat(operationsDeclaring("429")).isEqualTo(withABudget);
  }

  /**
   * Whether the production filter refuses a second call to {@code pathTemplate} with a budget of
   * one.
   */
  private static boolean isRateLimited(String pathTemplate) {
    String path = pathTemplate.replaceAll("\\{[^}]+}", PATH_VARIABLE);
    Filter filter = filterWithABudgetOfOne();
    MockHttpServletResponse response = new MockHttpServletResponse();
    for (int i = 0; i < 2; i++) {
      response = new MockHttpServletResponse();
      MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
      request.setRemoteAddr(CLIENT);
      try {
        filter.doFilter(request, response, new MockFilterChain());
      } catch (Exception e) {
        throw new IllegalStateException("filtering " + path + " failed", e);
      }
    }
    return response.getStatus() == 429;
  }

  private static Filter filterWithABudgetOfOne() {
    EndpointLimit one = new EndpointLimit(1, 60, 1000);
    LocalAuthLimit oneLocal = new LocalAuthLimit(1, 60, null, null);
    RateLimitProperties properties =
        new RateLimitProperties(
            true,
            List.of(),
            one,
            one,
            one,
            one,
            one,
            one,
            new LocalAuthLimits(
                oneLocal, oneLocal, oneLocal, oneLocal, oneLocal, oneLocal, oneLocal, oneLocal));
    return new RateLimitConfiguration()
        .rateLimitFilterRegistration(
            properties,
            new TrustedProxyClientIpResolver(List.of()),
            new RateLimitMetrics(new SimpleMeterRegistry()),
            JsonMapper.builder().build(),
            servedSelfService())
        .getFilter();
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<LocalSelfServiceAvailability> servedSelfService() {
    LocalSelfServiceAvailability served =
        new LocalSelfServiceAvailability() {

          @Override
          public boolean isPasswordResetAvailable() {
            return true;
          }

          @Override
          public boolean isSelfRegistrationAvailable() {
            return true;
          }
        };
    ObjectProvider<LocalSelfServiceAvailability> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable(any())).thenReturn(served);
    return provider;
  }

  private static Set<String> operationsDeclaring(String status) {
    Set<String> declaring = new TreeSet<>();
    forEachOperation(
        (path, method, operation) -> {
          if (map(operation, "responses").containsKey(status)) {
            declaring.add((String) operation.get("operationId"));
          }
        });
    return declaring;
  }

  private static Set<String> declaredStatusesOf(String operationId) {
    Set<String> statuses = new TreeSet<>();
    forEachOperation(
        (path, method, operation) -> {
          if (operationId.equals(operation.get("operationId"))) {
            statuses.addAll(map(operation, "responses").keySet());
          }
        });
    assertThat(statuses).as("no operation named %s", operationId).isNotEmpty();
    return statuses;
  }

  private static boolean consumesMultipart(Map<String, Object> operation) {
    Object requestBody = operation.get("requestBody");
    if (!(requestBody instanceof Map<?, ?> body)) {
      return false;
    }
    Object content = body.get("content");
    return content instanceof Map<?, ?> types && types.containsKey("multipart/form-data");
  }

  private interface OperationVisitor {
    void visit(String path, String method, Map<String, Object> operation);
  }

  @SuppressWarnings("unchecked")
  private static void forEachOperation(OperationVisitor visitor) {
    map(spec, "paths")
        .forEach(
            (path, item) ->
                ((Map<String, Object>) item)
                    .forEach(
                        (method, operation) -> {
                          if (HTTP_METHODS.contains(method)) {
                            visitor.visit(path, method, (Map<String, Object>) operation);
                          }
                        }));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> parent, String key) {
    Object value = parent.get(key);
    assertThat(value).as(key).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }
}
