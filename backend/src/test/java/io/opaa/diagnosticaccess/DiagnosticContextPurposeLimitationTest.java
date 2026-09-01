package io.opaa.diagnosticaccess;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.DiagnosticContextLogController;
import io.opaa.auth.CurrentUser;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.yaml.snakeyaml.Yaml;

/**
 * Leitplanke (g) is a Nicht-Existenz-Anforderung, so this is a Nicht-Existenz-Test: it asserts that
 * no read path in this codebase can express "Diagnosen je Nutzer", by checking the three places one
 * could be built - the repository's queries, the query service's signatures, and the published API
 * contract.
 *
 * <p><b>How much this guarantees, honestly:</b> it is a structural guard, not a proof. It cannot
 * stop someone with database access from writing {@code GROUP BY target_ref} by hand, and it cannot
 * stop a future feature that resolves target pseudonyms elsewhere. What it does guarantee is that
 * such a capability cannot appear through this application's own surface without this test turning
 * red - a new repository aggregate, a new target-person parameter on the protocol query, a new
 * count field on the response, or a new request parameter in the specification each fail one of the
 * assertions below.
 */
class DiagnosticContextPurposeLimitationTest {

  private static final Set<String> FORBIDDEN_QUERY_FRAGMENTS =
      Set.of("group by", "count(", "distinct");

  private static final Set<String> AGGREGATE_FIELD_MARKERS =
      Set.of("count", "total", "statistic", "summary", "ranking", "top");

  private static Map<String, Object> spec;

  @BeforeAll
  @SuppressWarnings("unchecked")
  static void loadSpec() {
    try (InputStream in =
        DiagnosticContextPurposeLimitationTest.class.getResourceAsStream(
            "/openapi/opaa-api.yaml")) {
      spec = new Yaml().load(in);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load opaa-api.yaml from the classpath", e);
    }
  }

  @Test
  void theRepositoryDeclaresNoAggregateQuery() {
    for (Method method : DiagnosticContextLogRepository.class.getDeclaredMethods()) {
      Query query = method.getAnnotation(Query.class);
      assertThat(query)
          .as("every declared method of the protocol repository must carry an explicit @Query")
          .isNotNull();
      String jpql = query.value().toLowerCase(Locale.ROOT);
      for (String forbidden : FORBIDDEN_QUERY_FRAGMENTS) {
        assertThat(jpql)
            .as("%s must not aggregate - see Leitplanke (g)", method.getName())
            .doesNotContain(forbidden);
      }
    }
  }

  /**
   * A Positivliste, not a blacklist: a read path may take the caller's own {@link CurrentUser}, a
   * time bound, a paging number, or the {@code reason} string - and nothing else. Checking only
   * against {@link UUID} would miss the shape the target person actually has in this model, a
   * {@link String} pseudonym ({@code target_ref} is a {@code varchar}), so a method taking one
   * would pass a blacklist untouched.
   */
  @Test
  void noProtocolReadPathTakesAnythingButTheCallersOwnIdentityTimeAndPaging() {
    Stream.concat(
            publicMethods(DiagnosticContextLogQueryService.class),
            publicMethods(DiagnosticContextLogController.class))
        .forEach(
            method -> {
              for (Parameter parameter : method.getParameters()) {
                assertThat(parameter.isNamePresent())
                    .as("compiled without -parameters; this guard would be meaningless")
                    .isTrue();
                assertThat(isAllowedReadParameter(parameter))
                    .as(
                        "%s.%s takes %s %s, which is not one of the parameters a protocol read path"
                            + " may have (own identity, time range, paging, reason)",
                        method.getDeclaringClass().getSimpleName(),
                        method.getName(),
                        parameter.getType().getSimpleName(),
                        parameter.getName())
                    .isTrue();
              }
            });
  }

  private static boolean isAllowedReadParameter(Parameter parameter) {
    Class<?> type = parameter.getType();
    String name = parameter.getName();
    if (type == CurrentUser.class) {
      return true;
    }
    if (type == Instant.class) {
      return Set.of("from", "to").contains(name);
    }
    if (type == int.class) {
      return Set.of("page", "size").contains(name);
    }
    return type == String.class && "reason".equals(name);
  }

  @Test
  void theProtocolPageCarriesNoAggregateField() {
    assertThat(propertiesOf("DiagnosticContextEventPage"))
        .containsOnlyKeys("events", "page", "size", "hasMore");
    assertThat(propertiesOf("OwnDiagnosticContextEventPage"))
        .containsOnlyKeys("events", "page", "size", "hasMore");

    Stream.of("DiagnosticContextEventResponse", "OwnDiagnosticContextEventResponse")
        .forEach(
            schema ->
                propertiesOf(schema)
                    .keySet()
                    .forEach(
                        field -> {
                          String lower = field.toLowerCase(Locale.ROOT);
                          AGGREGATE_FIELD_MARKERS.forEach(
                              marker ->
                                  assertThat(lower.contains(marker) && !"hitcount".equals(lower))
                                      .as("%s.%s looks like an aggregate", schema, field)
                                      .isFalse());
                        }));
  }

  @Test
  @SuppressWarnings("unchecked")
  void theProtocolEndpointOffersNoParameterNamingAPersonOrAGrouping() {
    Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
    Map<String, Object> operation =
        (Map<String, Object>)
            ((Map<String, Object>) paths.get("/api/v1/audit/diagnostic-context-events")).get("get");
    List<Map<String, Object>> parameters =
        (List<Map<String, Object>>) operation.getOrDefault("parameters", List.of());

    assertThat(parameters.stream().map(parameter -> (String) parameter.get("name")))
        .containsExactlyInAnyOrder("from", "to", "reason", "page", "size");

    Map<String, Object> ownOperation =
        (Map<String, Object>)
            ((Map<String, Object>) paths.get("/api/v1/me/diagnostic-context-events")).get("get");
    List<Map<String, Object>> ownParameters =
        (List<Map<String, Object>>) ownOperation.getOrDefault("parameters", List.of());
    assertThat(ownParameters.stream().map(parameter -> (String) parameter.get("name")))
        .containsExactlyInAnyOrder("page", "size");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> propertiesOf(String schemaName) {
    Map<String, Object> schemas =
        (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
    Map<String, Object> schema = (Map<String, Object>) schemas.get(schemaName);
    assertThat(schema).as("schema %s must exist", schemaName).isNotNull();
    return (Map<String, Object>) schema.get("properties");
  }

  private static Stream<Method> publicMethods(Class<?> type) {
    return Stream.of(type.getDeclaredMethods())
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .filter(method -> !method.isSynthetic());
  }
}
