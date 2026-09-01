package io.opaa.diagnosticaccess;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.DiagnosticContextLogController;
import io.opaa.auth.CurrentUser;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
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
   * The Gesamtprotokoll path must not accept a person. A {@link UUID} parameter would be the only
   * way to name one; {@link CurrentUser} is the caller's own, server-resolved identity and is
   * therefore the one identity-carrying parameter allowed.
   */
  @Test
  void noProtocolReadPathTakesATargetPerson() {
    Stream.concat(
            publicMethods(DiagnosticContextLogQueryService.class),
            publicMethods(DiagnosticContextLogController.class))
        .forEach(
            method -> {
              for (Parameter parameter : method.getParameters()) {
                if (parameter.getType() == CurrentUser.class) {
                  continue;
                }
                assertThat(parameter.getType())
                    .as(
                        "%s.%s must not take an identity other than the caller's own",
                        method.getDeclaringClass().getSimpleName(), method.getName())
                    .isNotEqualTo(UUID.class);
              }
            });
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
